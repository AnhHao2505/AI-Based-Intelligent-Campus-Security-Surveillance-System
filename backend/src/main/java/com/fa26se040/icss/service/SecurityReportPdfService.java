package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.report.SecurityReportSummaryDto;
import com.fa26se040.icss.dto.report.SecurityReportSummaryDto.*;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class SecurityReportPdfService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public byte[] generatePdf(SecurityReportSummaryDto report) {
        log.info("Generating native PDF for report [{} -> {}]", report.startDate(), report.endDate());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 28, 28, 30, 30);

        try {
            PdfWriter.getInstance(document, out);
            document.open();

            // Setup fonts with full UTF-8 Vietnamese support
            BaseFont bfRegular = resolveBaseFont(false);
            BaseFont bfBold = resolveBaseFont(true);

            Font fontOrg = new Font(bfBold, 10, Font.BOLD, new Color(0, 0, 0));
            Font fontOrgSub = new Font(bfRegular, 8.5f, Font.NORMAL, new Color(75, 85, 99));
            Font fontDocTitle = new Font(bfBold, 14, Font.BOLD, new Color(0, 0, 0));
            Font fontDocSub = new Font(bfRegular, 8.5f, Font.ITALIC, new Color(100, 116, 139));
            Font fontMeta = new Font(bfRegular, 8.5f, Font.NORMAL, new Color(30, 41, 59));
            Font fontMetaVal = new Font(bfBold, 8.5f, Font.BOLD, new Color(0, 0, 0));

            Font fontSecTitle = new Font(bfBold, 10, Font.BOLD, new Color(0, 0, 0));
            Font fontSecDesc = new Font(bfRegular, 8.5f, Font.NORMAL, new Color(51, 65, 85));

            Font fontTh = new Font(bfBold, 8, Font.BOLD, new Color(0, 0, 0));
            Font fontTd = new Font(bfRegular, 8, Font.NORMAL, new Color(15, 23, 42));
            Font fontTdBold = new Font(bfBold, 8, Font.BOLD, new Color(0, 0, 0));
            Font fontSign = new Font(bfBold, 9.5f, Font.BOLD, new Color(0, 0, 0));
            Font fontSignNote = new Font(bfRegular, 8, Font.ITALIC, new Color(100, 116, 139));

            // --- HEADER ---
            Paragraph pOrg = new Paragraph("TRƯỜNG ĐẠI HỌC FPT — PHÂN HIỆU TP. HỒ CHÍ MINH", fontOrg);
            Paragraph pOrgSub = new Paragraph("TRUNG TÂM VẬN HÀNH & GIÁM SÁT AN NINH THÔNG MINH (SOC) · CƠ SỞ TÂN UYÊN", fontOrgSub);
            document.add(pOrg);
            document.add(pOrgSub);

            // Thin rule
            document.add(new Paragraph(" "));
            Paragraph pTitle = new Paragraph("BÁO CÁO TỔNG HỢP GIÁM SÁT AN NINH & KIỂM SOÁT VÙNG HẠN CHẾ", fontDocTitle);
            pTitle.setAlignment(Element.ALIGN_CENTER);
            Paragraph pSub = new Paragraph("(Trích xuất tự động từ hệ thống AI Vision, nhật ký ra vào & phân công ca trực)", fontDocSub);
            pSub.setAlignment(Element.ALIGN_CENTER);
            pSub.setSpacingAfter(10);
            document.add(pTitle);
            document.add(pSub);

            // Metadata Box Table
            PdfPTable metaTable = new PdfPTable(3);
            metaTable.setWidthPercentage(100);
            metaTable.setSpacingAfter(12);

            PdfPCell c1 = createMetaCell("Chu kỳ báo cáo:", report.startDate().format(DATE_FMT) + " — " + report.endDate().format(DATE_FMT), fontMeta, fontMetaVal);
            PdfPCell c2 = createMetaCell("Số hiệu văn bản:", "SEC-RPT-" + report.generatedAt().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")), fontMeta, fontMetaVal);
            PdfPCell c3 = createMetaCell("Cán bộ lập báo cáo:", report.generatedByName() + " (" + report.generatedByEmail() + ")", fontMeta, fontMetaVal);
            metaTable.addCell(c1);
            metaTable.addCell(c2);
            metaTable.addCell(c3);
            document.add(metaTable);

            // --- SECTION I: EXECUTIVE SUMMARY ---
            addSectionHeader(document, "I. TỔNG HỢP CHỈ SỐ HOẠT ĐỘNG AN NINH (EXECUTIVE SUMMARY)", fontSecTitle);
            Paragraph pSec1 = new Paragraph(
                    "Trong khoảng thời gian từ ngày " + report.startDate().format(DATE_FMT) + " đến hết ngày " + report.endDate().format(DATE_FMT)
                            + ", hệ thống camera AI tại cơ sở Tân Uyên đã thực hiện giám sát liên tục các khu vực phân quyền (Public, Confidential, Highly Confidential). "
                            + "Toàn bộ luồng dữ liệu được đối soát tự động theo quy định kiểm soát truy cập và bảo vệ hiện trường.",
                    fontSecDesc
            );
            pSec1.setSpacingAfter(8);
            document.add(pSec1);

            PdfPTable tKpi = new PdfPTable(new float[]{40, 20, 40});
            tKpi.setWidthPercentage(100);
            tKpi.setSpacingAfter(12);
            addTh(tKpi, "Hạng mục chỉ số an ninh", fontTh, Element.ALIGN_LEFT);
            addTh(tKpi, "Số lượng ghi nhận", fontTh, Element.ALIGN_RIGHT);
            addTh(tKpi, "Ghi chú nghiệp vụ / Tỉ lệ", fontTh, Element.ALIGN_LEFT);

            addTd(tKpi, "Tổng số sự cố an ninh phát hiện", fontTdBold, Element.ALIGN_LEFT);
            addTd(tKpi, String.valueOf(report.totalIncidents()), fontTdBold, Element.ALIGN_RIGHT);
            addTd(tKpi, "Đã xác minh thật: " + report.verifiedIncidents() + " vụ · Báo nhầm: " + report.dismissedIncidents() + " (" + formatPct(report.falseAlarmRate()) + ")", fontTd, Element.ALIGN_LEFT);

            addTd(tKpi, "Sự cố đang chờ hoặc đang xử lý", fontTd, Element.ALIGN_LEFT);
            addTd(tKpi, String.valueOf(report.pendingIncidents()), fontTd, Element.ALIGN_RIGHT);
            addTd(tKpi, "Chưa tiếp nhận hoặc đang tiến hành xác minh thực địa", fontTd, Element.ALIGN_LEFT);

            addTd(tKpi, "Tổng lượt quét kiểm soát ra vào", fontTdBold, Element.ALIGN_LEFT);
            addTd(tKpi, String.format("%,d", report.totalAccessScans()), fontTdBold, Element.ALIGN_RIGHT);
            addTd(tKpi, "Hợp lệ: " + String.format("%,d", report.authorizedAccessCount()) + " · Vi phạm: " + report.violationAccessCount(), fontTd, Element.ALIGN_LEFT);

            addTd(tKpi, "Thời gian phản hồi tiếp nhận TB (Claim)", fontTd, Element.ALIGN_LEFT);
            addTd(tKpi, formatSec(report.avgResponseTimeSeconds()), fontTd, Element.ALIGN_RIGHT);
            addTd(tKpi, "Thời gian từ lúc AI báo động đến khi bảo vệ nhận việc trên ứng dụng", fontTd, Element.ALIGN_LEFT);

            addTd(tKpi, "Thời gian xử lý & kết luận TB (Resolve)", fontTd, Element.ALIGN_LEFT);
            addTd(tKpi, formatSec(report.avgResolutionTimeSeconds()), fontTd, Element.ALIGN_RIGHT);
            addTd(tKpi, "Thời gian kiểm tra hiện trường, lập biên bản hoặc nhắc nhở", fontTd, Element.ALIGN_LEFT);

            addTd(tKpi, "Tình trạng mạng lưới Camera giám sát", fontTd, Element.ALIGN_LEFT);
            addTd(tKpi, report.onlineCamerasCount() + "/" + report.activeCamerasCount(), fontTd, Element.ALIGN_RIGHT);
            addTd(tKpi, "Tỉ lệ trực tuyến đạt: " + (report.activeCamerasCount() > 0 ? formatPct((double) report.onlineCamerasCount() / report.activeCamerasCount()) : "100%"), fontTd, Element.ALIGN_LEFT);

            addTd(tKpi, "Thực thi kế hoạch ca trực bảo vệ", fontTd, Element.ALIGN_LEFT);
            addTd(tKpi, report.completedShiftsCount() + "/" + report.scheduledShiftsCount(), fontTd, Element.ALIGN_RIGHT);
            addTd(tKpi, "Tỉ lệ hoàn tất ca phân công: " + formatPct(report.shiftFulfillmentRate()), fontTd, Element.ALIGN_LEFT);

            addTd(tKpi, "Lưu lượng khách đăng ký thăm cơ sở", fontTd, Element.ALIGN_LEFT);
            addTd(tKpi, String.valueOf(report.totalGuestVisits()), fontTd, Element.ALIGN_RIGHT);
            addTd(tKpi, "Đã được phê duyệt hợp lệ & hoàn tất: " + report.approvedGuestVisits() + " lượt", fontTd, Element.ALIGN_LEFT);
            document.add(tKpi);

            // --- SECTION II: INCIDENT ANALYSIS ---
            addSectionHeader(document, "II. PHÂN TÍCH CHI TIẾT SỰ CỐ AN NINH & NGUY CƠ VI PHẠM", fontSecTitle);
            PdfPTable tInc = new PdfPTable(new float[]{30, 20, 20, 30});
            tInc.setWidthPercentage(100);
            tInc.setSpacingAfter(12);
            addTh(tInc, "Loại sự cố / Mức độ", fontTh, Element.ALIGN_LEFT);
            addTh(tInc, "Số vụ", fontTh, Element.ALIGN_RIGHT);
            addTh(tInc, "Tỉ lệ (%)", fontTh, Element.ALIGN_RIGHT);
            addTh(tInc, "Ghi chú phân loại", fontTh, Element.ALIGN_LEFT);

            if (report.eventTypeCounts() != null && !report.eventTypeCounts().isEmpty()) {
                for (CategoryCountDto item : report.eventTypeCounts()) {
                    addTd(tInc, item.label(), fontTd, Element.ALIGN_LEFT);
                    addTd(tInc, String.valueOf(item.count()), fontTd, Element.ALIGN_RIGHT);
                    addTd(tInc, formatPct(item.percentage()), fontTd, Element.ALIGN_RIGHT);
                    addTd(tInc, "AI Computer Vision phát hiện", fontTd, Element.ALIGN_LEFT);
                }
            }
            if (report.severityCounts() != null) {
                for (CategoryCountDto sev : report.severityCounts()) {
                    addTd(tInc, "[" + sev.label().toUpperCase() + "]", fontTdBold, Element.ALIGN_LEFT);
                    addTd(tInc, String.valueOf(sev.count()), fontTd, Element.ALIGN_RIGHT);
                    addTd(tInc, formatPct(sev.percentage()), fontTd, Element.ALIGN_RIGHT);
                    addTd(tInc, "Mức độ ưu tiên cảnh báo", fontTd, Element.ALIGN_LEFT);
                }
            }
            document.add(tInc);

            // Top Hotspot Areas & Resolution Categories
            PdfPTable tAreaRes = new PdfPTable(new float[]{50, 50});
            tAreaRes.setWidthPercentage(100);
            tAreaRes.setSpacingAfter(12);
            addTh(tAreaRes, "Khu vực phát sinh sự cố nhiều nhất", fontTh, Element.ALIGN_LEFT);
            addTh(tAreaRes, "Hình thức kết luận của lực lượng bảo vệ", fontTh, Element.ALIGN_LEFT);

            StringBuilder areaSb = new StringBuilder();
            if (report.topIncidentAreas() != null && !report.topIncidentAreas().isEmpty()) {
                for (AreaIncidentStatDto a : report.topIncidentAreas()) {
                    areaSb.append("• ").append(a.areaName()).append(" (").append(a.building()).append("): ").append(a.count()).append(" vụ\n");
                }
            } else {
                areaSb.append("Không ghi nhận khu vực vi phạm.");
            }

            StringBuilder resSb = new StringBuilder();
            if (report.resolutionCategoryCounts() != null && !report.resolutionCategoryCounts().isEmpty()) {
                for (CategoryCountDto r : report.resolutionCategoryCounts()) {
                    resSb.append("• ").append(r.label()).append(": ").append(r.count()).append(" vụ (").append(formatPct(r.percentage())).append(")\n");
                }
            } else {
                resSb.append("Chưa có kết luận sự cố được đóng.");
            }

            addTd(tAreaRes, areaSb.toString().trim(), fontTd, Element.ALIGN_LEFT);
            addTd(tAreaRes, resSb.toString().trim(), fontTd, Element.ALIGN_LEFT);
            document.add(tAreaRes);

            // --- SECTION III: ACCESS CONTROL BREAKDOWN ---
            addSectionHeader(document, "III. KIỂM SOÁT TRUY CẬP RA VÀO & VI PHẠM CHU VI", fontSecTitle);
            PdfPTable tAccess = new PdfPTable(new float[]{25, 45, 15, 15});
            tAccess.setWidthPercentage(100);
            tAccess.setSpacingAfter(12);
            addTh(tAccess, "Kết quả kiểm soát", fontTh, Element.ALIGN_LEFT);
            addTh(tAccess, "Quy định an ninh áp dụng", fontTh, Element.ALIGN_LEFT);
            addTh(tAccess, "Số lượt quét", fontTh, Element.ALIGN_RIGHT);
            addTh(tAccess, "Tỉ trọng (%)", fontTh, Element.ALIGN_RIGHT);

            if (report.accessResultCounts() != null) {
                for (CategoryCountDto res : report.accessResultCounts()) {
                    addTd(tAccess, "[" + res.label().toUpperCase() + "]", fontTdBold, Element.ALIGN_LEFT);
                    String ruleNote = switch (res.code()) {
                        case "AUTHORIZED_PERSON", "AUTHORIZED_ACCESS" -> "Nhận diện đúng người và đúng thẩm quyền khu vực";
                        case "UNAUTHORIZED_PERSON", "UNAUTHORIZED_ACCESS" -> "Có danh tính nhưng không đủ thẩm quyền hoặc ngoài khung giờ cho phép";
                        case "ACCESS_OUT_OF_TIME" -> "Có thẩm quyền nhưng tiếp cận ngoài khung giờ cho phép";
                        case "UNKNOWN_PERSON" -> "Đối tượng không có khuôn mặt trong cơ sở dữ liệu hệ thống";
                        case "PERSON_INTRUSION", "INTRUSION" -> "Xuất hiện trong vùng giám sát ngoài giờ hoạt động";
                        case "SUSPICIOUS_MOVING_OBJECT" -> "Chuyển động nghi vấn trong vùng ROI không gắn với người";
                        default -> "Quy định an ninh nội bộ";
                    };
                    addTd(tAccess, ruleNote, fontTd, Element.ALIGN_LEFT);
                    addTd(tAccess, String.format("%,d", res.count()), fontTd, Element.ALIGN_RIGHT);
                    addTd(tAccess, formatPct(res.percentage()), fontTd, Element.ALIGN_RIGHT);
                }
            }
            document.add(tAccess);

            // --- SECTION IV: GUARD ROSTER PERFORMANCE ---
            addSectionHeader(document, "IV. HOẠT ĐỘNG LỰC LƯỢNG BẢO VỆ & ĐIỀU PHỐI HIỆN TRƯỜNG", fontSecTitle);
            PdfPTable tGuard = new PdfPTable(new float[]{20, 40, 20, 20});
            tGuard.setWidthPercentage(100);
            tGuard.setSpacingAfter(12);
            addTh(tGuard, "Mã nhân viên", fontTh, Element.ALIGN_LEFT);
            addTh(tGuard, "Họ và tên cán bộ bảo vệ", fontTh, Element.ALIGN_LEFT);
            addTh(tGuard, "Số vụ đã xử lý", fontTh, Element.ALIGN_RIGHT);
            addTh(tGuard, "Tình trạng nhiệm vụ", fontTh, Element.ALIGN_LEFT);

            if (report.topGuards() != null && !report.topGuards().isEmpty()) {
                for (GuardIncidentStatDto g : report.topGuards()) {
                    addTd(tGuard, g.userCode(), fontTd, Element.ALIGN_LEFT);
                    addTd(tGuard, g.fullName(), fontTdBold, Element.ALIGN_LEFT);
                    addTd(tGuard, g.resolvedCount() + " vụ", fontTd, Element.ALIGN_RIGHT);
                    addTd(tGuard, "Đã hoàn tất báo cáo", fontTd, Element.ALIGN_LEFT);
                }
            } else {
                addTd(tGuard, "—", fontTd, Element.ALIGN_LEFT);
                addTd(tGuard, "Chưa ghi nhận ca trực xử lý sự cố trong kỳ", fontTd, Element.ALIGN_LEFT);
                addTd(tGuard, "0", fontTd, Element.ALIGN_RIGHT);
                addTd(tGuard, "—", fontTd, Element.ALIGN_LEFT);
            }
            document.add(tGuard);

            // --- SECTION V: SIGNIFICANT INCIDENTS LOG ---
            addSectionHeader(document, "V. NHẬT KÝ CHI TIẾT CÁC SỰ CỐ TRỌNG YẾU TRONG KỲ", fontSecTitle);
            PdfPTable tLog = new PdfPTable(new float[]{14, 18, 16, 10, 12, 12, 18});
            tLog.setWidthPercentage(100);
            tLog.setSpacingAfter(16);
            addTh(tLog, "Thời điểm", fontTh, Element.ALIGN_LEFT);
            addTh(tLog, "Vị trí & Camera", fontTh, Element.ALIGN_LEFT);
            addTh(tLog, "Hành vi vi phạm", fontTh, Element.ALIGN_LEFT);
            addTh(tLog, "Mức độ", fontTh, Element.ALIGN_CENTER);
            addTh(tLog, "Đối tượng", fontTh, Element.ALIGN_LEFT);
            addTh(tLog, "Bảo vệ xử lý", fontTh, Element.ALIGN_LEFT);
            addTh(tLog, "Kết luận & Ghi chú", fontTh, Element.ALIGN_LEFT);

            if (report.significantIncidents() != null && !report.significantIncidents().isEmpty()) {
                for (ReportIncidentItemDto inc : report.significantIncidents()) {
                    addTd(tLog, inc.detectedAt() != null ? inc.detectedAt().format(DATETIME_FMT) : "—", fontTd, Element.ALIGN_LEFT);
                    addTd(tLog, inc.location() + "\n(" + inc.cameraCode() + ")", fontTd, Element.ALIGN_LEFT);
                    addTd(tLog, inc.eventType(), fontTd, Element.ALIGN_LEFT);
                    addTd(tLog, "[" + inc.severity() + "]", fontTdBold, Element.ALIGN_CENTER);
                    addTd(tLog, inc.subjectUserCode(), fontTd, Element.ALIGN_LEFT);
                    addTd(tLog, inc.resolvedByName(), fontTd, Element.ALIGN_LEFT);
                    addTd(tLog, inc.resolutionCategory() + ": " + inc.resolutionNotes(), fontTd, Element.ALIGN_LEFT);
                }
            } else {
                PdfPCell emptyCell = new PdfPCell(new Phrase("Không ghi nhận sự cố vi phạm nghiêm trọng trong chu kỳ báo cáo.", fontTd));
                emptyCell.setColspan(7);
                emptyCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                emptyCell.setPadding(8);
                tLog.addCell(emptyCell);
            }
            document.add(tLog);

            // --- SECTION VI: OFFICIAL SIGN-OFF ---
            Paragraph pDisclaimer = new Paragraph(
                    "Căn cứ Quy chế Quản lý & Vận hành An ninh Cơ sở của Trường Đại học FPT, văn bản này là tài liệu kiểm toán nội bộ "
                            + "được kết xuất trực tiếp từ máy chủ cơ sở dữ liệu giám sát an ninh. Mọi hình ảnh snapshot, log đối soát khuôn mặt "
                            + "và biên bản xử lý vi phạm liên quan đã được lưu trữ an toàn tại hệ thống MinIO Storage phục vụ đối chiếu khi có yêu cầu.",
                    fontSecDesc
            );
            pDisclaimer.setSpacingAfter(14);
            document.add(pDisclaimer);

            PdfPTable tSign = new PdfPTable(2);
            tSign.setWidthPercentage(100);

            PdfPCell cSign1 = new PdfPCell();
            cSign1.setBorder(Rectangle.NO_BORDER);
            cSign1.setHorizontalAlignment(Element.ALIGN_CENTER);
            Paragraph pSign1 = new Paragraph("CÁN BỘ LẬP BÁO CÁO\n(Ký, ghi rõ họ tên và chức vụ)\n\n\n\n\n" + report.generatedByName() + "\n" + report.generatedByEmail(), fontSign);
            pSign1.setAlignment(Element.ALIGN_CENTER);
            cSign1.addElement(pSign1);

            PdfPCell cSign2 = new PdfPCell();
            cSign2.setBorder(Rectangle.NO_BORDER);
            cSign2.setHorizontalAlignment(Element.ALIGN_CENTER);
            Paragraph pSign2 = new Paragraph("BAN AN NINH & QUẢN LÝ CƠ SỞ\n(Xác nhận, phê duyệt và lưu trữ)\n\n\n\n\nBan Quản lý Cơ sở Tân Uyên\nĐại học FPT Phân hiệu TP.HCM", fontSign);
            pSign2.setAlignment(Element.ALIGN_CENTER);
            cSign2.addElement(pSign2);

            tSign.addCell(cSign1);
            tSign.addCell(cSign2);
            document.add(tSign);

            document.close();
        } catch (Exception e) {
            log.error("Error generating native PDF report: {}", e.getMessage(), e);
            throw new RuntimeException("Lỗi tạo file PDF: " + e.getMessage(), e);
        }

        return out.toByteArray();
    }

    private static BaseFont resolveBaseFont(boolean bold) {
        String path = bold ? "C:/Windows/Fonts/arialbd.ttf" : "C:/Windows/Fonts/arial.ttf";
        try {
            if (new File(path).exists()) {
                return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
            if (new File("C:/Windows/Fonts/times.ttf").exists()) {
                return BaseFont.createFont("C:/Windows/Fonts/times.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
            return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            try {
                return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    private static void addSectionHeader(Document doc, String title, Font font) throws DocumentException {
        Paragraph p = new Paragraph(title, font);
        p.setSpacingBefore(8);
        p.setSpacingAfter(4);
        doc.add(p);
    }

    private static void addTh(PdfPTable table, String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(new Color(241, 245, 249));
        cell.setBorderColor(new Color(113, 113, 122));
        cell.setHorizontalAlignment(align);
        cell.setPadding(5);
        table.addCell(cell);
    }

    private static void addTd(PdfPTable table, String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorderColor(new Color(161, 161, 170));
        cell.setHorizontalAlignment(align);
        cell.setPadding(4);
        table.addCell(cell);
    }

    private static PdfPCell createMetaCell(String label, String value, Font fLabel, Font fVal) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(new Color(250, 250, 250));
        cell.setBorderColor(new Color(212, 212, 216));
        cell.setPadding(6);
        cell.addElement(new Paragraph(label, fLabel));
        cell.addElement(new Paragraph(value, fVal));
        return cell;
    }

    private static String formatPct(Double pct) {
        if (pct == null) return "0.0%";
        return String.format("%.1f%%", pct * 100);
    }

    private static String formatSec(Long sec) {
        if (sec == null) return "—";
        if (sec < 60) return sec + "s";
        long m = sec / 60;
        long s = sec % 60;
        return s > 0 ? m + "m " + s + "s" : m + "m";
    }
}

