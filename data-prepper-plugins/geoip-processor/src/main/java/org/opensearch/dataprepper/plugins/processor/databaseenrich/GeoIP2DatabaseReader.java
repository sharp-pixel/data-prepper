/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.processor.databaseenrich;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.GeoIp2Exception;
import com.maxmind.geoip2.model.AsnResponse;
import com.maxmind.geoip2.model.EnterpriseResponse;
import com.maxmind.geoip2.record.City;
import com.maxmind.geoip2.record.Continent;
import com.maxmind.geoip2.record.Country;
import com.maxmind.geoip2.record.Location;
import com.maxmind.geoip2.record.Postal;
import com.maxmind.geoip2.record.RepresentedCountry;
import com.maxmind.geoip2.record.Subdivision;
import org.opensearch.dataprepper.plugins.processor.GeoIPDatabase;
import org.opensearch.dataprepper.plugins.processor.GeoIPField;
import org.opensearch.dataprepper.plugins.processor.exception.DatabaseReaderInitializationException;
import org.opensearch.dataprepper.plugins.processor.exception.EnrichFailedException;
import org.opensearch.dataprepper.plugins.processor.exception.NoValidDatabaseFoundException;
import org.opensearch.dataprepper.plugins.processor.extension.databasedownload.GeoIPFileManager;
import org.opensearch.dataprepper.plugins.processor.extension.databasedownload.DatabaseReaderBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class GeoIP2DatabaseReader implements GeoIPDatabaseReader, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(GeoIP2DatabaseReader.class);
    private static final String MAXMIND_GEOIP2_DATABASE_TYPE = "geoip2";
    private static final String ENTERPRISE_DATABASE = "enterprise";
    private final DatabaseReaderBuilder databaseReaderBuilder;
    private final String databasePath;
    private final int cacheSize;
    private final AtomicInteger closeCount;
    private final GeoIPFileManager geoIPFileManager;
    private final AtomicBoolean isEnterpriseDatabaseExpired;
    private DatabaseReader enterpriseDatabaseReader;
    private Instant enterpriseDatabaseBuildDate;

    public GeoIP2DatabaseReader(final DatabaseReaderBuilder databaseReaderBuilder,
                                final GeoIPFileManager geoIPFileManager,
                                final String databasePath, final int cacheSize) {
        this.databaseReaderBuilder = databaseReaderBuilder;
        this.geoIPFileManager = geoIPFileManager;
        this.databasePath = databasePath;
        this.cacheSize = cacheSize;
        closeCount = new AtomicInteger(1);
        this.isEnterpriseDatabaseExpired = new AtomicBoolean(false);
        buildDatabaseReaders();
    }

    private void buildDatabaseReaders() {
        try {
            final Optional<String> enterpriseDatabaseName = getDatabaseName(ENTERPRISE_DATABASE, databasePath, MAXMIND_GEOIP2_DATABASE_TYPE);

            if (enterpriseDatabaseName.isPresent()) {
                enterpriseDatabaseReader = databaseReaderBuilder.buildReader(Path.of(
                        databasePath + File.separator + enterpriseDatabaseName.get()), cacheSize);
                enterpriseDatabaseBuildDate = enterpriseDatabaseReader.getMetadata().getBuildDate().toInstant();
            }
        } catch (final IOException ex) {
            throw new DatabaseReaderInitializationException("Exception while creating GeoIP2 DatabaseReaders due to: " + ex.getMessage());
        }

        if (enterpriseDatabaseReader == null) {
            throw new NoValidDatabaseFoundException("Unable to initialize GeoIP2 database, make sure it is valid.");
        }
    }
    @Override
    public Map<String, Object> getGeoData(final InetAddress inetAddress, final List<GeoIPField> fields, final Set<GeoIPDatabase> geoIPDatabases) {
        Map<String, Object> geoData = new HashMap<>();

        try {
            if (geoIPDatabases.contains(GeoIPDatabase.ENTERPRISE)) {
                final Optional<EnterpriseResponse> optionalEnterpriseResponse = enterpriseDatabaseReader.tryEnterprise(inetAddress);
                optionalEnterpriseResponse.ifPresent(response -> processEnterpriseResponse(response, geoData, fields));
            }

            if (geoIPDatabases.contains(GeoIPDatabase.ASN)) {
                final Optional<AsnResponse> asnResponse = enterpriseDatabaseReader.tryAsn(inetAddress);
                asnResponse.ifPresent(response -> processAsnResponse(response, geoData, fields));
            }

        } catch (final GeoIp2Exception e) {
            throw new EnrichFailedException("Address not found in database.");
        } catch (final IOException e) {
            throw new EnrichFailedException("Failed to close database readers gracefully. It can be due to expired databases");
        }
        return geoData;
    }

    private void processEnterpriseResponse(final EnterpriseResponse enterpriseResponse, final Map<String, Object> geoData, final List<GeoIPField> fields) {
        final Continent continent = enterpriseResponse.getContinent();
        final Country country = enterpriseResponse.getCountry();
        final Country registeredCountry = enterpriseResponse.getRegisteredCountry();
        final RepresentedCountry representedCountry = enterpriseResponse.getRepresentedCountry();

        final City city = enterpriseResponse.getCity();
        final Location location = enterpriseResponse.getLocation();
        final Postal postal = enterpriseResponse.getPostal();
        final Subdivision mostSpecificSubdivision = enterpriseResponse.getMostSpecificSubdivision();
        final Subdivision leastSpecificSubdivision = enterpriseResponse.getLeastSpecificSubdivision();

        extractContinentFields(continent, geoData, fields);
        extractCountryFields(country, geoData, fields, true);
        extractRegisteredCountryFields(registeredCountry, geoData, fields);
        extractRepresentedCountryFields(representedCountry, geoData, fields);
        extractCityFields(city, geoData, fields, true);
        extractLocationFields(location, geoData, fields);
        extractPostalFields(postal, geoData, fields, true);
        extractMostSpecifiedSubdivisionFields(mostSpecificSubdivision, geoData, fields, true);
        extractLeastSpecifiedSubdivisionFields(leastSpecificSubdivision, geoData, fields, true);
    }

    private void processAsnResponse(final AsnResponse asnResponse, final Map<String, Object> geoData, final List<GeoIPField> fields) {
        extractAsnFields(asnResponse, geoData, fields);
    }

    @Override
    public boolean isExpired() {
        final Instant instant = Instant.now();
        if (enterpriseDatabaseReader == null) {
            return true;
        }
        if (isEnterpriseDatabaseExpired.get()) {
            return true;
        }
        if (enterpriseDatabaseBuildDate.plus(MAX_EXPIRY_DURATION).isBefore(instant)) {
            isEnterpriseDatabaseExpired.set(true);
            closeReader();
        }
        return isEnterpriseDatabaseExpired.get();
    }

    @Override
    public void retain() {
        closeCount.incrementAndGet();
    }

    @Override
    public void close() {
        final int count = closeCount.decrementAndGet();
        if (count == 0) {
            LOG.debug("Closing old geoip database readers");
            closeReader();
        }
    }

    private void closeReader() {
        try {
            if (enterpriseDatabaseReader != null) {
                enterpriseDatabaseReader.close();
            }
        } catch (final IOException e) {
            LOG.debug("Failed to close Maxmind database readers due to: {}. Force closing readers.", e.getMessage());
        }

        // delete database directory
        final File file = new File(databasePath);
        geoIPFileManager.deleteDirectory(file);
    }
}
