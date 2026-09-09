package com.hexvane.strangematter.anomaly;

import java.util.UUID;

/** Store token() in item metadata; a copied or already released token cannot create another anomaly. */
public record CapturedAnomaly(UUID id, AnomalyType type, String token) {
    public String itemId() { return type.capsuleItemId(); }
}
