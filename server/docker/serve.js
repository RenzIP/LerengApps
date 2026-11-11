'use strict';

/**
 * Pembungkus HTTP biasa untuk handler Vercel di ../api/ingest.js.
 *
 * Tujuannya supaya kontrak ingest hanya ada satu sumber: file yang sama dipakai
 * saat deploy ke Vercel maupun saat dijalankan di Docker. File ini hanya
 * menyediakan `req.body` dan `res.status().json()` yang biasanya disediakan
 * platform Vercel.
 */

const http = require('http');
const handler = require('../api/ingest');

const PORT = Number(process.env.PORT || 8080);
const MAX_BODY_BYTES = 4 * 1024 * 1024;

function makeResponse(res) {
  let statusCode = 200;
  const headers = {};

  const api = {
    setHeader(name, value) {
      headers[name] = value;
    },
    getHeader(name) {
      return headers[name];
    },
    status(code) {
      statusCode = code;
      return api;
    },
    json(payload) {
      const body = JSON.stringify(payload);
      res.writeHead(statusCode, Object.assign({}, headers, {
        'Content-Length': Buffer.byteLength(body),
      }));
      res.end(body);
      return api;
    },
    send(payload) {
      const body = typeof payload === 'string' ? payload : JSON.stringify(payload);
      res.writeHead(statusCode, Object.assign({}, headers, {
        'Content-Length': Buffer.byteLength(body),
      }));
      res.end(body);
      return api;
    },
  };
  return api;
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        reject(Object.assign(new Error('body terlalu besar'), { statusCode: 413 }));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    req.on('error', reject);
  });
}

/** Status konfigurasi tanpa membocorkan nilai rahasia. */
function health() {
  return {
    ok: true,
    service: 'lereng-offline-ingest',
    configured: Boolean(process.env.MONGODB_URI && process.env.INGEST_TOKEN),
    hasMongoUri: Boolean(process.env.MONGODB_URI),
    hasToken: Boolean(process.env.INGEST_TOKEN),
    database: process.env.MONGODB_DB || 'lereng',
    collection: process.env.MONGODB_COLL || 'features',
  };
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', 'http://localhost');

  if (url.pathname === '/health' || url.pathname === '/api/health') {
    const body = JSON.stringify(health());
    res.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
      'Content-Length': Buffer.byteLength(body),
    });
    res.end(body);
    return;
  }

  // Terima /api/ingest (bentuk Vercel) dan /ingest (kalau di balik reverse proxy).
  if (url.pathname !== '/api/ingest' && url.pathname !== '/ingest') {
    const body = JSON.stringify({ ok: false, error: 'not_found' });
    res.writeHead(404, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': Buffer.byteLength(body),
    });
    res.end(body);
    return;
  }

  try {
    req.body = await readBody(req);
  } catch (error) {
    const body = JSON.stringify({ ok: false, error: 'body_too_large' });
    res.writeHead(error.statusCode || 400, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': Buffer.byteLength(body),
    });
    res.end(body);
    return;
  }

  try {
    await handler(req, makeResponse(res));
  } catch (error) {
    console.error('ingest crash', error && error.name);
    if (!res.headersSent) {
      const body = JSON.stringify({ ok: false, error: 'internal_error' });
      res.writeHead(500, {
        'Content-Type': 'application/json; charset=utf-8',
        'Content-Length': Buffer.byteLength(body),
      });
      res.end(body);
    }
  }
});

server.listen(PORT, () => {
  const state = health();
  console.log(`ingest siap di port ${PORT}`);
  console.log(`konfigurasi: ${state.configured ? 'lengkap' : 'BELUM lengkap (MONGODB_URI/INGEST_TOKEN)'}`);
});

function shutdown(signal) {
  console.log(`${signal} diterima, menutup server`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 5000).unref();
}

process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
