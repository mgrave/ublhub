/*
 * Copyright 2019 Project OpenUBL, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Eclipse Public License - v 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.project.openubl.xsender.scheduler.commons;

import io.github.project.openubl.xmlsenderws.webservices.providers.BillServiceModel;
import io.github.project.openubl.xmlsenderws.webservices.xml.XmlContentModel;
import io.github.project.openubl.xsender.exceptions.*;
import io.github.project.openubl.xsender.files.FilesMutiny;
import io.github.project.openubl.xsender.models.ErrorType;
import io.github.project.openubl.xsender.models.jpa.CompanyRepository;
import io.github.project.openubl.xsender.models.jpa.UBLDocumentRepository;
import io.github.project.openubl.xsender.models.jpa.entities.UBLDocumentEntity;
import io.github.project.openubl.xsender.sender.XSenderConfig;
import io.github.project.openubl.xsender.sender.XSenderConfigBuilder;
import io.github.project.openubl.xsender.sender.XSenderMutiny;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.HashSet;
import java.util.Optional;

@ApplicationScoped
public class EventManagerUtils {

    @Inject
    FilesMutiny filesMutiny;

    @Inject
    XSenderMutiny xSenderMutiny;

    @Inject
    UBLDocumentRepository documentRepository;

    @Inject
    CompanyRepository companyRepository;

    public Uni<DocumentUniSend> initDocumentUniSend(String documentId) {
        return Panache
                .withTransaction(() -> documentRepository
                        .findById(documentId)
                        .onItem().ifNull().failWith(() -> new IllegalStateException("Document id=" + documentId + " was not found"))
                        .onItem().ifNotNull().transform(documentEntity -> DocumentUniSendBuilder.aDocumentUniSend()
                                .withNamespaceId(documentEntity.namespace.id)
                                .withId(documentEntity.id)
                                .withRetries(documentEntity.retries)
                                .withXmlFileId(documentEntity.storageFile)
                                .build()
                        )
                )
                .invoke(documentUni -> {
                    documentUni.setError(null);
                    documentUni.setInProgress(false);
                    documentUni.setScheduledDelivery(null);

                    documentUni.setFileValid(null);
                });
    }

    public Uni<DocumentUniTicket> initDocumentUniTicket(String documentId) {
        return Panache
                .withTransaction(() -> documentRepository
                        .findById(documentId)
                        .onItem().ifNull().failWith(() -> new IllegalStateException("Document id=" + documentId + " was not found"))
                        .onItem().ifNotNull().transform(documentEntity -> {
                                    XmlContentModel xmlContent = XmlContentModel.Builder.aXmlContentModel()
                                            .withRuc(documentEntity.ruc)
                                            .withDocumentType(documentEntity.documentType)
                                            .withDocumentID(documentEntity.documentID)
                                            .withVoidedLineDocumentTypeCode(documentEntity.voidedLineDocumentTypeCode)
                                            .build();

                                    return DocumentUniTicketBuilder.aDocumentUniTicket()
                                            .withNamespaceId(documentEntity.namespace.id)
                                            .withId(documentEntity.id)
                                            .withTicket(documentEntity.sunatTicket)
                                            .withXmlContent(xmlContent)
                                            .build();
                                }
                        )
                )
                .invoke(documentUniTicket -> {
                    documentUniTicket.setError(null);
                    documentUniTicket.setInProgress(false);
                    documentUniTicket.setScheduledDelivery(null);
                });
    }

    public Uni<byte[]> enrichWithFileAsBytes(DocumentUniSend documentUni) {
        return filesMutiny
                .getFileAsBytesAfterUnzip(documentUni.getXmlFileId())
                .onFailure(throwable -> throwable instanceof FetchFileException).retry().atMost(1)
                .onFailure(throwable -> throwable instanceof FetchFileException).invoke(throwable -> {
                    documentUni.setError(ErrorType.FETCH_FILE);
                })
                .invoke(documentUni::setFile);
    }

    public Uni<XmlContentModel> enrichWithFileContent(DocumentUniSend documentUni, byte[] fileBytes) {
        return xSenderMutiny
                .getFileContent(fileBytes)
                .onFailure(throwable -> throwable instanceof ReadFileException).invoke(throwable -> {
                    ReadFileException readFileException = (ReadFileException) throwable;

                    XmlContentModel xmlContentModel = new XmlContentModel();
                    xmlContentModel.setDocumentType(readFileException.getDocumentType());

                    documentUni.setError(readFileException.getDocumentType() == null ? ErrorType.READ_FILE : ErrorType.UNSUPPORTED_DOCUMENT_TYPE);
                    documentUni.setFileValid(false);
                    documentUni.setXmlContent(xmlContentModel);
                })
                .invoke(xmlContentModel -> {
                    documentUni.setFileValid(true);
                    documentUni.setXmlContent(xmlContentModel);
                });
    }

    public Uni<XSenderConfig> enrichWithWsConfig(DocumentUniSend documentUni, XmlContentModel xmlContentModel) {
        return xSenderMutiny
                .getXSenderConfig(documentUni.getNamespaceId(), xmlContentModel.getRuc())
                .onFailure(throwable -> throwable instanceof NoCompanyWithRucException).invoke(throwable -> {
                    documentUni.setError(ErrorType.COMPANY_NOT_FOUND);
                })
                .invoke(documentUni::setWsConfig);
    }

    public Uni<XSenderConfig> enrichWithWsConfig(DocumentUniTicket documentUni) {
        return companyRepository
                .findByRuc(documentUni.getNamespaceId(), documentUni.getXmlContent().getRuc())
                .onItem().ifNull().failWith(() -> new NoCompanyWithRucException("No company with ruc found"))
                .onItem().ifNotNull().transform(companyEntity -> XSenderConfigBuilder.aXSenderConfig()
                        .withFacturaUrl(companyEntity.sunatUrls.sunatUrlFactura)
                        .withGuiaUrl(companyEntity.sunatUrls.sunatUrlGuiaRemision)
                        .withPercepcionRetencionUrl(companyEntity.sunatUrls.sunatUrlPercepcionRetencion)
                        .withUsername(companyEntity.sunatCredentials.sunatUsername)
                        .withPassword(companyEntity.sunatCredentials.sunatPassword)
                        .build()
                )
                .invoke(documentUni::setWsConfig);
    }

    public Uni<BillServiceModel> enrichWithSendingFile(DocumentUniSend documentUni, XSenderConfig wsConfig, int numberOfRetryAttempts) {
        Uni<BillServiceModel> billServiceModelUni = xSenderMutiny.sendFile(documentUni.getFile(), wsConfig);
        if (numberOfRetryAttempts > 0) {
            billServiceModelUni = billServiceModelUni
                    .onFailure(throwable -> throwable instanceof SendFileToSUNATException)
                    .retry().atMost(numberOfRetryAttempts);
        }

        return billServiceModelUni
                .onFailure(throwable -> throwable instanceof SendFileToSUNATException).invoke(throwable -> {
                    documentUni.setError(ErrorType.SEND_FILE);
                })
                .invoke(documentUni::setBillServiceModel);
    }

    public Uni<BillServiceModel> enrichWithCheckingTicket(DocumentUniTicket documentUni, int numberOfRetryAttempts) {
        Uni<BillServiceModel> billServiceModelUni = xSenderMutiny.verifyTicket(documentUni.getTicket(), documentUni.getXmlContent(), documentUni.getWsConfig());
        if (numberOfRetryAttempts > 0) {
            billServiceModelUni = billServiceModelUni
                    .onFailure(throwable -> throwable instanceof CheckTicketAtSUNATException)
                    .retry().atMost(numberOfRetryAttempts);
        }

        return billServiceModelUni
                .onFailure(throwable -> throwable instanceof CheckTicketAtSUNATException).invoke(throwable -> {
                    documentUni.setError(ErrorType.CHECK_TICKET);
                })
                .invoke(documentUni::setBillServiceModel);
    }

    public Uni<Optional<String>> enrichSavingCDRIfExists(DocumentUni documentUni, BillServiceModel billServiceModel) {
        return Uni.createFrom()
                .item(billServiceModel.getCdr())
                .onItem().ifNotNull().transformToUni(cdrBytes -> filesMutiny
                        .createFile(cdrBytes, false)
                        .map(Optional::of)
                        .onFailure(throwable -> throwable instanceof SaveFileException).invoke(throwable -> {
                            documentUni.setError(ErrorType.SAVE_CRD_FILE);
                        })
                )
                .onItem().ifNull().continueWith(Optional::empty)
                .invoke(cdrFileIdOptional -> {
                    documentUni.setCdrFileId(cdrFileIdOptional.orElse(null));
                });
    }

    public Uni<UBLDocumentEntity> documentUniToEntity(DocumentUniSend documentUni) {
        return Panache.withTransaction(() -> documentRepository.findById(documentUni.getId())
                .onItem().ifNull().failWith(() -> new IllegalStateException("Document id=" + documentUni.getId() + " was not found"))
                .invoke(documentEntity -> {
                    documentEntity.error = documentUni.getError();
                    documentEntity.fileValid = documentUni.getFileValid();
                    documentEntity.inProgress = documentUni.getError() == null && documentUni.getBillServiceModel() != null && documentUni.getBillServiceModel().getTicket() != null;
                    documentEntity.scheduledDelivery = documentUni.getScheduledDelivery();

                    documentEntity.retries = documentUni.getRetries() != null ? documentUni.getRetries() : 0;

                    if (documentUni.getXmlContent() != null) {
                        documentEntity.ruc = documentUni.getXmlContent().getRuc();
                        documentEntity.documentID = documentUni.getXmlContent().getDocumentID();
                        documentEntity.documentType = documentUni.getXmlContent().getDocumentType();
                        documentEntity.voidedLineDocumentTypeCode = documentUni.getXmlContent().getVoidedLineDocumentTypeCode();
                    }

                    if (documentUni.getBillServiceModel() != null) {
                        documentEntity.sunatStatus = documentUni.getBillServiceModel().getStatus() != null ? documentUni.getBillServiceModel().getStatus().toString() : null;
                        documentEntity.sunatCode = documentUni.getBillServiceModel().getCode();
                        documentEntity.sunatDescription = documentUni.getBillServiceModel().getDescription();
                        documentEntity.sunatTicket = documentUni.getBillServiceModel().getTicket();
                        documentEntity.sunatNotes = documentUni.getBillServiceModel().getNotes() != null ? new HashSet<>(documentUni.getBillServiceModel().getNotes()) : new HashSet<>();
                    }

                    documentEntity.storageCdr = documentUni.getCdrFileId();
                })
        );
    }

    public Uni<UBLDocumentEntity> documentUniToEntity(DocumentUniTicket documentUni) {
        return Panache.withTransaction(() -> documentRepository.findById(documentUni.getId())
                .onItem().ifNull().failWith(() -> new IllegalStateException("Document id=" + documentUni.getId() + " was not found"))
                .invoke(documentEntity -> {
                    documentEntity.error = documentUni.getError();
                    documentEntity.inProgress = false;
                    documentEntity.scheduledDelivery = documentUni.getScheduledDelivery();

                    documentEntity.retries = documentUni.getRetries() != null ? documentUni.getRetries() : 0;

                    if (documentUni.getBillServiceModel() != null) {
                        documentEntity.sunatStatus = documentUni.getBillServiceModel().getStatus() != null ? documentUni.getBillServiceModel().getStatus().toString() : null;
                        documentEntity.sunatCode = documentUni.getBillServiceModel().getCode();
                        documentEntity.sunatDescription = documentUni.getBillServiceModel().getDescription();
                        documentEntity.sunatTicket = documentUni.getBillServiceModel().getTicket();
                        documentEntity.sunatNotes = documentUni.getBillServiceModel().getNotes() != null ? new HashSet<>(documentUni.getBillServiceModel().getNotes()) : new HashSet<>();
                    }

                    documentEntity.storageCdr = documentUni.getCdrFileId();
                })
        );
    }
}