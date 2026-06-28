package dev.sindic.enrollmenthub.geoscoring.service;

/**
 * Geocoded coordinates returned from cache or the geocoding provider (Nominatim).
 */
public record CoordinatesPayload(double latitude, double longitude) {}
