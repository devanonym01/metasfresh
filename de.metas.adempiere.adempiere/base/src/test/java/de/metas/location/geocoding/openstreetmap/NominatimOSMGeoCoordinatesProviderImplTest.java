package de.metas.location.geocoding.openstreetmap;

import de.metas.location.geocoding.GeographicalCoordinates;
import de.metas.location.geocoding.GeoCoordinatesRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * #%L
 * metasfresh-pharma
 * %%
 * Copyright (C) 2018 metas GmbH
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program. If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */

@SuppressWarnings("ArraysAsListWithZeroOrOneArgument") @Disabled("It makes real queries which can't be mocked so don't run it automatically.")
class NominatimOSMGeoCoordinatesProviderImplTest
{
	public static final long MILLIS_BETWEEN_REQUESTS = TimeUnit.SECONDS.toMillis(20);
	private NominatimOSMGeoCoordinatesProviderImpl coordinatesProvider;

	@BeforeEach
	void beforeEach()
	{
		coordinatesProvider = new NominatimOSMGeoCoordinatesProviderImpl("", MILLIS_BETWEEN_REQUESTS, 0);
	}

	@Test
	@DisplayName("singleCoordinate; countryCode: no; postal: no; address: 'zzzzzzzzzz'")
	void singleCoordinate_CountryCodeNo_PostalNo_Addresszzzzzzzzzzzzzz()
	{
		final Optional<GeographicalCoordinates> coord = coordinatesProvider.findBestCoordinates(
				GeoCoordinatesRequest.builder()
						.address("zzzzzzzzzzzzzzzzzzzzzzzzzzz")
						.countryCode("")
						.build());

		assertThat(coord).isEmpty();
	}

	@Test
	@DisplayName("bestCoordinates finds metasRO only by postal")
	void bestCoordinatesFindsMetasROOnlyByPostal()
	{
		final Optional<GeographicalCoordinates> coord = coordinatesProvider.findBestCoordinates(
				GeoCoordinatesRequest.builder()
						.postal("300078")
						.countryCode("")
						.build());

		final GeographicalCoordinates expectedCoordinates = new GeographicalCoordinates("45.758301112052", "21.2249884579613");

		assertThat(coord)
				.isNotEmpty()
				.contains(expectedCoordinates);
	}

	@Test
	@DisplayName("bestCoordinates cannot find metasRO by postal and wrong country")
	void bestCoordinatesCannotFindMetasROByPostalAndWrongCountry()
	{
		final Optional<GeographicalCoordinates> coord = coordinatesProvider.findBestCoordinates(
				GeoCoordinatesRequest.builder()
						.postal("300078")
						.countryCode("UK")
						.build());

		assertThat(coord).isEmpty();
	}

	@Test
	@DisplayName("bestCoordinates finds metasRO by postal and RO country")
	void bestCoordinatesFindsMetasROByPostalAndCorrectCountry()
	{
		final Optional<GeographicalCoordinates> coord = coordinatesProvider.findBestCoordinates(
				GeoCoordinatesRequest.builder()
						.postal("300078")
						.countryCode("RO")
						.build());

		final GeographicalCoordinates expectedCoordinates = new GeographicalCoordinates("45.758301112052", "21.2249884579613");

		assertThat(coord)
				.isNotEmpty()
				.contains(expectedCoordinates);
	}

	@Test
	@DisplayName("Should ignore the address if a postal code exists")
	void shouldIgnoreTheAddressIfAPostalCodeExists()
	{
		final List<GeographicalCoordinates> coord = coordinatesProvider.findAllCoordinates(
				GeoCoordinatesRequest.builder()
						.postal("5081")
						.countryCode("AT")
						.address("gfvgdggsdfsdfgsdfgsdfgsdfgnull")
						.build());

		final List<GeographicalCoordinates> expectedCoordinates = Arrays.asList(new GeographicalCoordinates("47.7587073", "13.0612349838947"));

		assertThat(coord)
				.isNotEmpty()
				.containsAll(expectedCoordinates);
	}

	@Test
	@DisplayName("Should ignore the address if a postal code exists2")
	void shouldIgnoreTheAddressIfAPostalCodeExists2()
	{
		final List<GeographicalCoordinates> coord = coordinatesProvider.findAllCoordinates(
				GeoCoordinatesRequest.builder()
						.postal("5081")
						.countryCode("AT")
						.build());

		final List<GeographicalCoordinates> expectedCoordinates = Arrays.asList(new GeographicalCoordinates("47.7587073", "13.0612349838947"));

		assertThat(coord)
				.isNotEmpty()
				.containsAll(expectedCoordinates);
	}

	@Test
	@DisplayName("Search for postal code in wrong country has no result")
	void searchForPostalCodeInWrongCountryHasNoResult()
	{
		final Optional<GeographicalCoordinates> coord = coordinatesProvider.findBestCoordinates(
				GeoCoordinatesRequest.builder()
						.postal("5081")
						.countryCode("RO")
						.build());

		assertThat(coord)
				.isEmpty();
	}

	@Test
	@DisplayName("2 different requests at the same time should be rate limited")
	void expect2DifferentRequestsAtTheSameTimeShouldBeRateLimited()
	{
		coordinatesProvider = new NominatimOSMGeoCoordinatesProviderImpl("", MILLIS_BETWEEN_REQUESTS, 5);

		final Instant start = Instant.now();
		coordinatesProvider.findBestCoordinates(
				GeoCoordinatesRequest.builder()
						.postal("5081")
						.countryCode("RO")
						.build());

		coordinatesProvider.findBestCoordinates(
				GeoCoordinatesRequest.builder()
						.postal("5082")
						.countryCode("RO")
						.build());

		assertThat(Duration.between(start, Instant.now()))
				.isGreaterThan(Duration.ofMillis(MILLIS_BETWEEN_REQUESTS))
				.isBetween(Duration.ofMillis(MILLIS_BETWEEN_REQUESTS), Duration.ofMillis((long)(1.8 * MILLIS_BETWEEN_REQUESTS)));
	}

	@Test
	@DisplayName("expect cache hit for duplicate requests")
	void expectCacheHitForDuplicateRequests()
	{
		coordinatesProvider = new NominatimOSMGeoCoordinatesProviderImpl("", MILLIS_BETWEEN_REQUESTS, 5);
		final GeoCoordinatesRequest req = GeoCoordinatesRequest.builder()
				.postal("5081")
				.countryCode("AT")
				.build();
		coordinatesProvider.findBestCoordinates(req);

		final Instant start = Instant.now();

		coordinatesProvider.findBestCoordinates(req);
		coordinatesProvider.findBestCoordinates(req);
		coordinatesProvider.findBestCoordinates(req);
		coordinatesProvider.findBestCoordinates(req);

		assertThat(Duration.between(start, Instant.now()))
				.isLessThan(Duration.ofMillis(MILLIS_BETWEEN_REQUESTS));
	}
}
