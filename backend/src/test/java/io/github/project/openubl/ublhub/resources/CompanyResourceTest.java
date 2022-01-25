/*
 * Copyright 2019 Project OpenUBL, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.project.openubl.ublhub.resources;

import io.github.project.openubl.ublhub.AbstractBaseTest;
import io.github.project.openubl.ublhub.ProfileManager;
import io.github.project.openubl.ublhub.idm.CompanyRepresentation;
import io.github.project.openubl.ublhub.idm.CompanyRepresentationBuilder;
import io.github.project.openubl.ublhub.idm.SunatCredentialsRepresentation;
import io.github.project.openubl.ublhub.idm.SunatUrlsRepresentation;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

@QuarkusTest
@TestProfile(ProfileManager.class)
public class CompanyResourceTest extends AbstractBaseTest {

    @Override
    public Class<?> getTestClass() {
        return CompanyResourceTest.class;
    }

    @Test
    public void getCompany() {
        // Given
        String nsId = "1";
        String companyId = "11";

        // When
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + nsId + "/companies/" + companyId)
                .then()
                .statusCode(200)
                .body("id", is(notNullValue()),
                        "name", is("company1"),
                        "ruc", is("11111111111"),
                        "webServices.factura", is("http://urlFactura1"),
                        "webServices.guia", is("http://urlGuia1"),
                        "webServices.retenciones", is("http://urlPercepcionRetencion1")
                );
        // Then
    }

    @Test
    public void getCompanyThatBelongsToOtherNamespace_shouldNotBeAllowed() {
        // Given
        String nsOwnerId = "1";
        String nsToTestId = "2";

        String companyId = "11";

        // When
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + nsOwnerId + "/companies/" + companyId)
                .then()
                .statusCode(200);

        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + nsToTestId + "/companies/" + companyId)
                .then()
                .statusCode(404);
        // Then
    }

    @Test
    public void createCompany() {
        // Given
        String nsId = "1";

        CompanyRepresentation company = CompanyRepresentationBuilder.aCompanyRepresentation()
                .withName("My company")
                .withRuc("12345678910")
                .withWebServices(SunatUrlsRepresentation.Builder.aSunatUrlsRepresentation()
                        .withFactura("http://url1.com")
                        .withGuia("http://url2.com")
                        .withRetenciones("http://url3.com")
                        .build()
                )
                .withCredentials(SunatCredentialsRepresentation.Builder.aSunatCredentialsRepresentation()
                        .withUsername("myUsername")
                        .withPassword("myPassword")
                        .build()
                )
                .build();

        // When
        CompanyRepresentation response = given()
                .contentType(ContentType.JSON)
                .body(company)
                .when()
                .post("/api/namespaces/" + nsId + "/companies")
                .then()
                .statusCode(200)
                .body("id", is(notNullValue()),
                        "name", is(company.getName()),
                        "webServices.factura", is(company.getWebServices().getFactura()),
                        "webServices.guia", is(company.getWebServices().getGuia()),
                        "webServices.retenciones", is(company.getWebServices().getRetenciones()),
                        "credentials.username", is(company.getCredentials().getUsername()),
                        "credentials.password", nullValue()
                ).extract().body().as(CompanyRepresentation.class);

        // Then
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + nsId + "/companies/" + response.getId())
                .then()
                .statusCode(200)
                .body("id", is(response.getId()),
                        "name", is(company.getName()),
                        "webServices.factura", is(company.getWebServices().getFactura()),
                        "webServices.guia", is(company.getWebServices().getGuia()),
                        "webServices.retenciones", is(company.getWebServices().getRetenciones()),
                        "credentials.username", is(company.getCredentials().getUsername()),
                        "credentials.password", nullValue()
                );
    }

    @Test
    public void create2CompaniesWithSameRuc_shouldNotBeAllowed() {
        // Given
        String nsId = "1";

        CompanyRepresentation company = CompanyRepresentationBuilder.aCompanyRepresentation()
                .withName("My company")
                .withRuc("11111111111")
                .withWebServices(SunatUrlsRepresentation.Builder.aSunatUrlsRepresentation()
                        .withFactura("http://url1.com")
                        .withGuia("http://url2.com")
                        .withRetenciones("http://url3.com")
                        .build()
                )
                .withCredentials(SunatCredentialsRepresentation.Builder.aSunatCredentialsRepresentation()
                        .withUsername("myUsername")
                        .withPassword("myPassword")
                        .build()
                )
                .build();

        // When
        given()
                .contentType(ContentType.JSON)
                .body(company)
                .when()
                .post("/api/namespaces/" + nsId + "/companies")
                .then()
                .statusCode(409);
        // Then
    }

    @Test
    public void updateCompany() {
        // Given
        String nsId = "1";
        String companyId = "11";

        CompanyRepresentation companyRepresentation = CompanyRepresentationBuilder.aCompanyRepresentation()
                .withRuc("99999999999")
                .withName("new name")
                .withDescription("new description")
                .withWebServices(SunatUrlsRepresentation.Builder.aSunatUrlsRepresentation()
                        .withFactura("http://newUrl1.com")
                        .withRetenciones("http://newUrl2.com")
                        .withGuia("http://newUrl3.com")
                        .build()
                )
                .withCredentials(SunatCredentialsRepresentation.Builder.aSunatCredentialsRepresentation()
                        .withUsername("new username")
                        .withPassword("new password")
                        .build()
                )
                .build();

        // When
        given()
                .contentType(ContentType.JSON)
                .body(companyRepresentation)
                .when()
                .put("/api/namespaces/" + nsId + "/companies/" + companyId)
                .then()
                .statusCode(200)
                .body("ruc", is(companyRepresentation.getRuc()),
                        "name", is(companyRepresentation.getName()),
                        "description", is(companyRepresentation.getDescription()),
                        "webServices.factura", is(companyRepresentation.getWebServices().getFactura()),
                        "webServices.retenciones", is(companyRepresentation.getWebServices().getRetenciones()),
                        "webServices.guia", is(companyRepresentation.getWebServices().getGuia()),
                        "credentials.username", is(companyRepresentation.getCredentials().getUsername()),
                        "credentials.password", is(nullValue())
                );

        // Then
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + nsId + "/companies/" + companyId)
                .then()
                .statusCode(200)
                .body("id", is(companyId),
                        "ruc", is(companyRepresentation.getRuc()),
                        "name", is(companyRepresentation.getName()),
                        "description", is(companyRepresentation.getDescription()),
                        "webServices.factura", is(companyRepresentation.getWebServices().getFactura()),
                        "webServices.retenciones", is(companyRepresentation.getWebServices().getRetenciones()),
                        "webServices.guia", is(companyRepresentation.getWebServices().getGuia()),
                        "credentials.username", is(companyRepresentation.getCredentials().getUsername()),
                        "credentials.password", is(nullValue())
                );
    }

    @Test
    public void updateCompanyWithIncorrectNs_shouldNotBeAllowed() {
        String nsId = "1";
        String companyId = "33";

        // Given
        CompanyRepresentation companyRepresentation = CompanyRepresentationBuilder.aCompanyRepresentation()
                .withRuc("99999999999")
                .withName("new name")
                .withDescription("new description")
                .withWebServices(SunatUrlsRepresentation.Builder.aSunatUrlsRepresentation()
                        .withFactura("http://newUrl1.com")
                        .withRetenciones("http://newUrl2.com")
                        .withGuia("http://newUrl3.com")
                        .build()
                )
                .withCredentials(SunatCredentialsRepresentation.Builder.aSunatCredentialsRepresentation()
                        .withUsername("new username")
                        .withPassword("new password")
                        .build()
                )
                .build();

        // When
        given()
                .contentType(ContentType.JSON)
                .body(companyRepresentation)
                .when()
                .put("/api/namespaces/" + nsId + "/companies/" + companyId)
                .then()
                .statusCode(404);
        // Then
    }

    @Test
    public void deleteCompany() {
        // Given
        String nsId = "1";
        String companyId = "11";

        // When
        given()
                .contentType(ContentType.JSON)
                .when()
                .delete("/api/namespaces/" + nsId + "/companies/" + companyId)
                .then()
                .statusCode(204);

        // Then
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + nsId + "/companies/" + companyId)
                .then()
                .statusCode(404);
    }

    @Test
    public void deleteCompanyByIncorrectNs_shouldNotBeAllowed() {
        // Given
        String nsId = "1";
        String companyId = "33";

        // When
        given()
                .contentType(ContentType.JSON)
                .when()
                .delete("/api/namespaces/" + nsId + "/companies/" + companyId)
                .then()
                .statusCode(404);
        // Then
    }

    @Test
    public void searchCompanies() {
        // Given
        String nsId = "1";

        // When
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + nsId + "/companies")
                .then()
                .statusCode(200)
                .body("meta.count", is(2),
                        "items.size()", is(2),
                        "items[0].name", is("company2"),
                        "items[1].name", is("company1")
                );

        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + nsId + "/companies?sort_by=created:asc")
                .then()
                .statusCode(200)
                .body("meta.count", is(2),
                        "items.size()", is(2),
                        "items[0].name", is("company1"),
                        "items[1].name", is("company2")
                );
        // Then
    }

    @Test
    public void searchCompanies_filterTextByName() {
        // Given
        String nsId = "1";

        // When
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + nsId + "/companies?filterText=company1")
                .then()
                .statusCode(200)
                .body("meta.count", is(1),
                        "items.size()", is(1),
                        "items[0].name", is("company1")
                );
        // Then
    }

    @Test
    public void searchCompanies_filterTextByRuc() {
        // Given
        String nsId = "1";

        // When
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/api/namespaces/" + nsId + "/companies?filterText=222222")
                .then()
                .statusCode(200)
                .body("meta.count", is(1),
                        "items.size()", is(1),
                        "items[0].name", is("company2")
                );
        // Then
    }

}
