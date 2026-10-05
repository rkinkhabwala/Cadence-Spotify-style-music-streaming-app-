package com.cadence.catalog.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("cadence.catalog")
record CatalogProperties(long maxUploadBytes, Duration uploadUrlTtl) {
}
