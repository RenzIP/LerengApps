'use strict';

/**
 * Lereng Offline ingest endpoint.
 *
 * Menerima satu batch Fitur GeoJSON dari aplikasi Android/iOS, lalu menulisnya
 * ke MongoDB sebagai satu dokumen per Fitur. Hanya endpoint ini yang memegang
 * MONGODB_URI; APK/IPA tidak pernah menyimpan kredensial basis data.
 *
 * Variabel lingkungan:
 *   MONGODB_URI     wajib, connection string Atlas
 *   INGEST_TOKEN    wajib, token acak yang juga diisi di aplikasi
 *   MONGODB_DB      opsional, default "lereng"
 *   MONGODB_COLL    opsional, default "features"
 */

const crypto = require('crypto');
const { MongoClient } = require('mongodb');

const MAX_BODY_BYTES = 4 * 1024 * 1024;
const MAX_FEATURES = 800;
const MAX_DOC_BYTES = 512 * 1024;
const MAX_DEPTH = 6;

const RECORD_TYPES = new Set(['track', 'waypoint', 'presence', 'project']);
const RECORD_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;

let cachedClient = null;
let cachedIndexes = null;

function dbName() {
  return process.env.MONGODB_DB || 'lereng';
}

function collectionName() {
  return process.env.MONGODB_COLL || 'features';
}

async function getCollection() {
  if (!cachedClient) {
    const client = new MongoClient(process.env.MONGODB_URI, {
      maxPoolSize: 5,
      serverSelectionTimeoutMS: 8000,
      connectTimeoutMS: 8000,
    });
    cachedClient = client.connect().then((connected) => {
      const collection = connected.db(dbName()).collection(collectionName());
      if (!cachedIndexes) {
        cachedIndexes = Promise.allSettled([
          collection.createIndex({ geometry: '2dsphere' }, { name: 'geometry_2dsphere' }),
          collection.createIndex({ 'properties.project': 1 }, { name: 'project_1' }),
          collection.createIndex({ 'properties.record_type': 1 }, { name: 'record_type_1' }),
          collection.createIndex({ 'properties.time_ms': 1 }, { name: 'time_ms_1' }),
        ]);
      }
      return cachedIndexes.then(() => collection);
    });
    cachedClient.catch(() => {
      cachedClient = null;
      cachedIndexes = null;
    });
  }
  return cachedClient;
}

function safeEqual(left, right) {
  const a = Buffer.from(String(left || ''), 'utf8');
  const b = Buffer.from(String(right || ''), 'utf8');
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function authorized(req) {
  const header = req.headers.authorization || '';
  return safeEqual(header, `Bearer ${process.env.INGEST_TOKEN || ''}`);
}

function fail(res, status, code, detail) {
  res.status(status).json({ ok: false, error: code, detail: detail || undefined });
}

/**
 * Tolak nama field yang tidak bisa disimpan MongoDB atau yang bisa dipakai
 * untuk menyuntik operator. Data berasal dari aplikasi kita, tetapi endpoint
 * ini tetap publik dan harus defensif.
 */
function checkProperties(value, depth) {
  if (depth > MAX_DEPTH) throw new Error('properties terlalu dalam');
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return;
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error('angka tidak valid');
    return;
  }
  if (Array.isArray(value)) {
    if (value.length > 20000) throw new Error('array terlalu panjang');
    for (const item of value) checkProperties(item, depth + 1);
    return;
  }
  if (typeof value !== 'object') throw new Error('tipe properti tidak didukung');
  for (const [key, child] of Object.entries(value)) {
    if (!key || key.startsWith('$') || key.includes('.') || key.includes('\u0000')) {
      throw new Error(`nama field tidak valid: ${key}`);
    }
    checkProperties(child, depth + 1);
  }
}

function validCoordinate(geometry) {
  if (!geometry || geometry.type !== 'Point' || !Array.isArray(geometry.coordinates)) return false;
  const coordinates = geometry.coordinates;
  if (coordinates.length !== 2 && coordinates.length !== 3) return false;
  if (!coordinates.every((value) => typeof value === 'number' && Number.isFinite(value))) return false;
  const [lon, lat] = coordinates;
  if (lon < -180 || lon > 180 || lat < -90 || lat > 90) return false;
  return true;
}

function toDocument(feature, envelope) {
  if (!feature || feature.type !== 'Feature') throw new Error('bukan Fitur GeoJSON');
  const properties = feature.properties;
  if (!properties || typeof properties !== 'object' || Array.isArray(properties)) {
    throw new Error('properties wajib berupa objek');
  }
  checkProperties(properties, 0);

  const recordId = properties.record_id;
  if (typeof recordId !== 'string' || !RECORD_ID.test(recordId)) {
    throw new Error('record_id tidak valid');
  }
  const recordType = properties.record_type;
  if (!RECORD_TYPES.has(recordType)) throw new Error('record_type tidak dikenal');

  const geometry = feature.geometry === undefined ? null : feature.geometry;
  if (recordType === 'presence' || recordType === 'project') {
    if (geometry !== null) throw new Error(`${recordType} tidak boleh punya geometri`);
  } else if (!validCoordinate(geometry)) {
    throw new Error('geometri Point tidak valid');
  }

  const storedProperties = Object.assign({}, properties);
  delete storedProperties.record_id;

  const document = {
    _id: recordId,
    type: 'Feature',
    geometry,
    properties: storedProperties,
    ingest: {
      client_id: envelope.client_id,
      platform: envelope.platform,
      app_version: envelope.app_version,
      project: envelope.project,
      survey_id: envelope.survey_id,
      received_at: new Date().toISOString(),
    },
  };

  if (Buffer.byteLength(JSON.stringify(document), 'utf8') > MAX_DOC_BYTES) {
    throw new Error('dokumen terlalu besar');
  }
  return document;
}

module.exports = async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('Content-Type', 'application/json; charset=utf-8');

  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    return fail(res, 405, 'method_not_allowed');
  }
  if (!process.env.MONGODB_URI || !process.env.INGEST_TOKEN) {
    return fail(res, 500, 'server_not_configured');
  }
  if (!authorized(req)) {
    return fail(res, 401, 'unauthorized');
  }
  const length = Number(req.headers['content-length'] || 0);
  if (length > MAX_BODY_BYTES) {
    return fail(res, 413, 'body_too_large');
  }

  let body = req.body;
  if (typeof body === 'string') {
    try {
      body = JSON.parse(body);
    } catch {
      return fail(res, 400, 'invalid_json');
    }
  }
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    return fail(res, 400, 'invalid_json');
  }
  if (body.schema !== 'lereng.mongo/1') {
    return fail(res, 400, 'unsupported_schema');
  }

  const envelope = {
    client_id: typeof body.client_id === 'string' ? body.client_id.slice(0, 128) : '',
    platform: typeof body.platform === 'string' ? body.platform.slice(0, 32) : '',
    app_version: typeof body.app_version === 'string' ? body.app_version.slice(0, 32) : '',
    project: typeof body.project === 'string' ? body.project.slice(0, 200) : '',
    survey_id: Number.isFinite(body.survey_id) ? body.survey_id : null,
  };
  if (!envelope.client_id) return fail(res, 400, 'missing_client_id');

  const features = body.features;
  if (!Array.isArray(features) || features.length === 0) {
    return fail(res, 400, 'missing_features');
  }
  if (features.length > MAX_FEATURES) {
    return fail(res, 413, 'too_many_features', `maksimum ${MAX_FEATURES} per permintaan`);
  }

  let documents;
  try {
    documents = features.map((feature) => toDocument(feature, envelope));
  } catch (error) {
    return fail(res, 400, 'invalid_feature', error.message);
  }

  let collection;
  try {
    collection = await getCollection();
  } catch {
    return fail(res, 503, 'database_unavailable');
  }

  try {
    const result = await collection.bulkWrite(
      documents.map((document) => ({
        replaceOne: {
          filter: { _id: document._id },
          replacement: document,
          upsert: true,
        },
      })),
      { ordered: false }
    );
    return res.status(200).json({
      ok: true,
      received: documents.length,
      inserted: result.upsertedCount || 0,
      updated: result.modifiedCount || 0,
      matched: result.matchedCount || 0,
    });
  } catch (error) {
    // Jangan bocorkan connection string atau detail driver ke klien.
    console.error('ingest failed', error && error.name, error && error.code);
    return fail(res, 502, 'write_failed');
  }
};
