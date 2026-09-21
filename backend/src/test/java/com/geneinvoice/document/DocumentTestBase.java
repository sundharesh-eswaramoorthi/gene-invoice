package com.geneinvoice.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.geneinvoice.IntegrationTestBase;
import com.geneinvoice.config.DataSeeder;
import com.geneinvoice.customer.Customer;
import com.geneinvoice.invoice.Invoice;
import com.geneinvoice.invoice.InvoiceDtos;
import com.geneinvoice.invoice.InvoiceService;
import com.geneinvoice.payment.Payment;
import com.geneinvoice.payment.PaymentDtos;
import com.geneinvoice.payment.PaymentService;
import com.geneinvoice.product.Product;
import com.geneinvoice.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class DocumentTestBase extends IntegrationTestBase {

    @Autowired protected InvoiceService invoiceService;
    @Autowired protected PaymentService paymentService;
    @Autowired protected DocumentProperties documentProperties;
    @Autowired protected MultipartProperties multipartProperties;

    protected User admin;
    protected User cashier;
    protected User viewer;
    protected User sales;
    protected User otherSales;
    protected User collector;
    protected Customer acme;
    protected Customer globex;
    protected User acmeLogin;
    protected User globexLogin;
    protected Product widget;
    protected Invoice acmeInvoice;
    protected Invoice globexInvoice;

    @BeforeEach
    void documentFixtures() {
        admin = userRepository.findByUsername("admin").orElseThrow();
        cashier = userRepository.findByUsername("cashier").orElseThrow();
        viewer = user("vera.viewer", "VIEWER");
        sales = user("sam.sales", DataSeeder.ROLE_SALES_POC);
        otherSales = user("sid.sales", DataSeeder.ROLE_SALES_POC);
        collector = user("cora.collect", DataSeeder.ROLE_COLLECTION_POC);
        acme = customer("Acme Ltd", "ap@acme.test");
        globex = customer("Globex Corp", "ap@globex.test");
        acmeLogin = customerUser("acme.login", acme.getId());
        globexLogin = customerUser("globex.login", globex.getId());
        widget = product("Widget", "1200.00");
        actAs(admin);
        acmeInvoice = invoice(acme, sales);
        globexInvoice = invoice(globex, otherSales);
    }

    protected Invoice invoice(Customer customer, User salesPoc) {
        actAs(admin);
        return invoiceService.create(new InvoiceDtos.CreateInvoiceRequest(customer.getId(), null, null,
                salesPoc.getId(), List.of(new InvoiceDtos.LineInput(widget.getId(), 1, null))));
    }

    protected Payment payment(Customer customer, String amount) {
        actAs(admin);
        return paymentService.record(new PaymentDtos.CreatePaymentRequest(customer.getId(),
                new BigDecimal(amount), "Cash", null, List.of(), collector.getId(), null));
    }

    protected static byte[] pdf() {
        return "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n%%EOF\n"
                .getBytes(StandardCharsets.UTF_8);
    }

    protected static byte[] png() {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        return concat(signature, "IHDR-and-the-rest".getBytes(StandardCharsets.UTF_8));
    }

    protected static byte[] jpeg() {
        byte[] soi = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        return concat(soi, "JFIF".getBytes(StandardCharsets.UTF_8));
    }

    protected static byte[] docx() {
        return openXml("word/document.xml");
    }

    protected static byte[] xlsx() {
        return openXml("xl/workbook.xml");
    }

    protected static byte[] plainZip() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("hello".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    protected static byte[] plainText() {
        return "this is not a document the app accepts".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] openXml(String part) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(part));
            zip.write("<xml/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] all = new byte[head.length + tail.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(tail, 0, all, head.length, tail.length);
        return all;
    }

    protected static MockMultipartFile part(String filename, byte[] bytes, String claimedType) {
        return new MockMultipartFile("file", filename, claimedType, bytes);
    }

    protected MockHttpServletRequestBuilder uploadRequest(User as, String entityType, Long entityId,
                                                          MockMultipartFile file) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/documents");
        return request.file(file)
                .param("entityType", entityType)
                .param("entityId", String.valueOf(entityId))
                .with(as(as));
    }

    protected JsonNode upload(User as, String entityType, Long entityId, String filename) throws Exception {
        return read(mockMvc.perform(uploadRequest(as, entityType, entityId,
                        part(filename, pdf(), "application/pdf")))
                .andExpect(status().isCreated()));
    }

    protected JsonNode read(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
