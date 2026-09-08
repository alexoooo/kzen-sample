package tech.kzen.sample.embed.host.catalog;

import java.net.URI;
import java.time.LocalDate;

public record ItchCatalogFile(String id, LocalDate date, URI url, long size, URI checksum) {}
