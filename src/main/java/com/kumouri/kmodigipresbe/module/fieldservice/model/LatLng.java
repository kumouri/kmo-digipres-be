package com.kumouri.kmodigipresbe.module.fieldservice.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Stored as a GeoJSON-style {@code {type: "Point", coordinates: [lng, lat]}} when used
 * with Mongo's {@code 2dsphere} index. We keep the @Data class shape so Spring Data can
 * convert it cleanly. NOTE: in GeoJSON the order is (longitude, latitude) — easy to flip.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LatLng {

    /**
     * GeoJSON type — must be the string {@code "Point"}.
     */
    @Builder.Default
    private String type = "Point";

    /**
     * {@code [longitude, latitude]} per the GeoJSON spec.
     */
    private double[] coordinates;

    public static LatLng of(double lat, double lng) {
        return new LatLng("Point", new double[]{lng, lat});
    }

    public double latitude() {
        return coordinates == null || coordinates.length < 2 ? 0 : coordinates[1];
    }

    public double longitude() {
        return coordinates == null || coordinates.length < 2 ? 0 : coordinates[0];
    }
}
