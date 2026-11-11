import SwiftUI
import MapKit
import UIKit

/// Peta rute tanpa layanan pihak ketiga. Ubin peta berasal dari kerangka peta sistem.
/// Jalur pembanding dari GPX pihak lain digambar putus-putus dan tidak pernah digabung
/// ke titik rekaman.
struct RouteMapView: UIViewRepresentable {
    var points: [TrackPoint]
    var reference: [ReferencePoint] = []
    var referenceLabel: String = ""

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MKMapView {
        let map = MKMapView()
        map.delegate = context.coordinator
        map.isRotateEnabled = false
        map.isPitchEnabled = false
        map.showsCompass = false
        map.showsScale = false
        map.pointOfInterestFilter = .excludingAll
        return map
    }

    func updateUIView(_ mapView: MKMapView, context: Context) {
        let signature = "\(points.count)|\(points.last?.id ?? -1)|\(reference.count)|\(reference.first?.lat ?? 0)|\(referenceLabel)"
        guard context.coordinator.signature != signature else { return }
        context.coordinator.signature = signature

        mapView.accessibilityLabel = reference.isEmpty
            ? "Peta jalur offline"
            : "Peta jalur offline dengan jalur pembanding \(referenceLabel)"

        mapView.removeOverlays(mapView.overlays)
        mapView.removeAnnotations(mapView.annotations)

        let track = points
            .filter { !$0.isWaypoint && $0.hasValidCoordinate }
            .sorted { $0.time < $1.time }

        var coordinates = track.map {
            CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
        }

        if coordinates.count >= 2 {
            let polyline = MKPolyline(coordinates: &coordinates, count: coordinates.count)
            mapView.addOverlay(polyline)
        }

        // Garis pembanding dipecah saat ada lompatan besar supaya tidak menarik garis lurus
        // melintasi lembah ketika file sumber punya jeda sinyal.
        var segment: [CLLocationCoordinate2D] = []
        var previous: ReferencePoint?
        func flushSegment() {
            guard segment.count >= 2 else { segment.removeAll(); return }
            var line = segment
            mapView.addOverlay(MKGeodesicPolyline(coordinates: &line, count: line.count))
            segment.removeAll()
        }
        for point in reference where point.hasValidCoordinate {
            if let previous,
               Geo.distanceMeters(lat1: previous.lat, lon1: previous.lon,
                                  lat2: point.lat, lon2: point.lon) > 2000 {
                flushSegment()
            }
            segment.append(CLLocationCoordinate2D(latitude: point.lat, longitude: point.lon))
            previous = point
        }
        flushSegment()

        for waypoint in points where waypoint.isWaypoint && waypoint.hasValidCoordinate {
            let annotation = MKPointAnnotation()
            annotation.coordinate = CLLocationCoordinate2D(latitude: waypoint.lat, longitude: waypoint.lon)
            annotation.title = waypoint.label.isEmpty ? "Waypoint" : waypoint.label
            if let altitude = waypoint.altitude {
                annotation.subtitle = Format.meters(altitude)
            }
            mapView.addAnnotation(annotation)
        }

        let focus = coordinates.first
            ?? reference.first(where: { $0.hasValidCoordinate })
                .map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon) }
        guard let focus else { return }
        mapView.setRegion(MKCoordinateRegion(center: focus,
                                             latitudinalMeters: 1200,
                                             longitudinalMeters: 1200),
                          animated: false)

        var rect = MKMapRect.null
        for overlay in mapView.overlays {
            rect = rect.union(overlay.boundingMapRect)
        }
        if !rect.isNull {
            mapView.setVisibleMapRect(rect,
                                      edgePadding: UIEdgeInsets(top: 44, left: 44, bottom: 44, right: 44),
                                      animated: false)
        }
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var signature = ""

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let polyline = overlay as? MKPolyline else {
                return MKOverlayRenderer(overlay: overlay)
            }
            let renderer = MKPolylineRenderer(polyline: polyline)
            if polyline is MKGeodesicPolyline {
                renderer.strokeColor = UIColor(Theme.warning)
                renderer.lineWidth = 2.5
                renderer.lineDashPattern = [NSNumber(value: 6), NSNumber(value: 5)]
                renderer.alpha = 0.9
            } else {
                renderer.strokeColor = UIColor(Theme.accent)
                renderer.lineWidth = 4
            }
            renderer.lineCap = .round
            renderer.lineJoin = .round
            return renderer
        }
    }
}
