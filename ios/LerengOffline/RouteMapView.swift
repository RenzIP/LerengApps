import SwiftUI
import MapKit
import UIKit

/// Peta rute tanpa layanan pihak ketiga. Ubin peta berasal dari kerangka peta sistem.
struct RouteMapView: UIViewRepresentable {
    var points: [TrackPoint]

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
        let signature = "\(points.count)|\(points.last?.id ?? -1)"
        guard context.coordinator.signature != signature else { return }
        context.coordinator.signature = signature

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

        for waypoint in points where waypoint.isWaypoint && waypoint.hasValidCoordinate {
            let annotation = MKPointAnnotation()
            annotation.coordinate = CLLocationCoordinate2D(latitude: waypoint.lat, longitude: waypoint.lon)
            annotation.title = waypoint.label.isEmpty ? "Waypoint" : waypoint.label
            if let altitude = waypoint.altitude {
                annotation.subtitle = Format.meters(altitude)
            }
            mapView.addAnnotation(annotation)
        }

        guard let first = coordinates.first else { return }
        mapView.setRegion(MKCoordinateRegion(center: first,
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
            renderer.strokeColor = UIColor(Theme.accent)
            renderer.lineWidth = 4
            renderer.lineCap = .round
            renderer.lineJoin = .round
            return renderer
        }
    }
}
