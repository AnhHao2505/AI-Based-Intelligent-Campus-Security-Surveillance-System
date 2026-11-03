import { jsPDF } from 'jspdf';

/**
 * Creates a clean, institutional, monochrome PDF blob for demo mode
 * using pure vector jsPDF commands without DOM or CSS parsing.
 * This ensures 0 dependency on html2canvas and avoids any oklch color parsing issues.
 */
export async function generateDemoPdfBlob({ startDate, endDate }) {
  const doc = new jsPDF({
    orientation: 'portrait',
    unit: 'mm',
    format: 'a4',
  });

  const pageWidth = doc.internal.pageSize.getWidth();
  const margin = 14;
  let y = 16;

  // Header
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(10);
  doc.setTextColor(0, 0, 0);
  doc.text('TRUONG DAI HOC FPT - PHAN HIEU TP. HO CHI MINH', margin, y);
  y += 5;

  doc.setFont('helvetica', 'normal');
  doc.setFontSize(8);
  doc.setTextColor(80, 80, 80);
  doc.text('TRUNG TAM VAN HANH & GIAM SAT AN NINH THONG MINH (SOC) - CO SO TAN UYEN', margin, y);
  y += 8;

  doc.setDrawColor(180, 180, 180);
  doc.setLineWidth(0.3);
  doc.line(margin, y, pageWidth - margin, y);
  y += 8;

  // Title
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(13);
  doc.setTextColor(0, 0, 0);
  doc.text('BAO CAO TONG HOP GIAM SAT AN NINH & KIEM SOAT VUNG HAN CHE', pageWidth / 2, y, { align: 'center' });
  y += 5;

  doc.setFont('helvetica', 'italic');
  doc.setFontSize(8);
  doc.setTextColor(100, 100, 100);
  doc.text('(Trich xuat tu he thong AI Vision, nhat ky ra vao & phan cong ca truc)', pageWidth / 2, y, { align: 'center' });
  y += 8;

  // Metadata Box
  doc.setFillColor(248, 248, 248);
  doc.rect(margin, y, pageWidth - (margin * 2), 14, 'F');
  doc.setDrawColor(210, 210, 210);
  doc.rect(margin, y, pageWidth - (margin * 2), 14, 'S');

  doc.setFont('helvetica', 'normal');
  doc.setFontSize(8);
  doc.setTextColor(40, 40, 40);
  doc.text(`Chu ky bao cao: ${startDate} - ${endDate}`, margin + 4, y + 5);
  doc.text(`Ma bao cao: SEC-RPT-${startDate.replace(/-/g, '')}`, margin + 4, y + 10);
  doc.text('Can bo lap: Administrator (admin@fpt.edu.vn)', margin + 70, y + 5);
  doc.text('Trang thai he thong: ONLINE (97.5% Camera)', margin + 70, y + 10);
  y += 20;

  // Section 1
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(9.5);
  doc.setTextColor(0, 0, 0);
  doc.text('I. TONG HOP CHI SO HOAT DONG AN NINH (EXECUTIVE SUMMARY)', margin, y);
  y += 5;

  doc.setFont('helvetica', 'normal');
  doc.setFontSize(8);
  doc.setTextColor(40, 40, 40);
  const summaryText = `Trong giai doan tu ${startDate} den ${endDate}, he thong giam sat an ninh co so Tan Uyen da ghi nhan day du du lieu kiem soat truy cap, hoat dong tuan tra va cac canh bao bat thuong.`;
  doc.text(summaryText, margin, y, { maxWidth: pageWidth - (margin * 2) });
  y += 8;

  // KPI Table
  const drawRow = (col1, col2, col3, isHeader = false) => {
    const rowHeight = 6;
    if (isHeader) {
      doc.setFillColor(240, 240, 240);
      doc.rect(margin, y, pageWidth - (margin * 2), rowHeight, 'FD');
      doc.setFont('helvetica', 'bold');
      doc.setFontSize(7.5);
      doc.setTextColor(0, 0, 0);
    } else {
      doc.setDrawColor(220, 220, 220);
      doc.rect(margin, y, pageWidth - (margin * 2), rowHeight, 'S');
      doc.setFont('helvetica', 'normal');
      doc.setFontSize(7.5);
      doc.setTextColor(20, 20, 20);
    }

    doc.text(col1, margin + 2, y + 4.2);
    doc.text(col2, margin + 85, y + 4.2);
    doc.text(col3, margin + 120, y + 4.2);
    y += rowHeight;
  };

  drawRow('Hang muc chi so', 'Ghi nhan', 'Ty le / Chu thich', true);
  drawRow('Tong so su co an ninh phat hien', '10 vu', 'Xac minh dung: 8 vu - Bao nham: 2 (20.0%)');
  drawRow('Tong luot quet kiem soat ra vao', '1,500 luot', 'Hop le: 1,480 luot - Vi pham: 20');
  drawRow('Thoi gian phan hoi trung binh', '45 giay', 'Bao ve tiep nhan tren app');
  drawRow('Thoi gian xu ly trung binh', '180 giay', 'Lap bien ban hoac giai quyet');
  drawRow('Mang luoi Camera giam sat', '39/40 hoat dong', 'Ty le truc tuyen 97.5%');
  drawRow('Thuc thi ca truc bao ve', '20/20 ca truc', 'Ty le hoan thanh 100%');
  drawRow('Khach dang ky tham quan', '15 luot', 'Phe duyet va vao ra hop le 14 luot');
  y += 6;

  // Section 2
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(9.5);
  doc.setTextColor(0, 0, 0);
  doc.text('II. PHAN TICH CHI TIET SU CO AN NINH THEO MUC DO', margin, y);
  y += 5;

  drawRow('Loai vi pham / Muc do', 'So vu', 'Ty trong / Danh gia', true);
  drawRow('Xam nhap ngoai gio (PERSON_INTRUSION)', '4 vu', '40.0% - Phat hien qua hang rao ao');
  drawRow('Nguoi la khong nhan dien (UNKNOWN_PERSON)', '3 vu', '30.0% - Canh bao doi tuong chua dang ky');
  drawRow('Thieu tham quyen / sai khung gio (UNAUTHORIZED_PERSON)', '3 vu', '30.0% - Vao phong lab muon, quet the phong may chu');
  y += 6;

  // Section 3
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(9.5);
  doc.setTextColor(0, 0, 0);
  doc.text('III. NHAT KY SU CO TRONG YEU', margin, y);
  y += 5;

  drawRow('Thoi diem & Vi tri', 'Doi tuong', 'Ket luan xu ly', true);
  drawRow('02/11 14:15 - Cong so 1 (CAM-01)', 'Sinh vien SV-00124', 'Nhac nho - Thu quet the ngoai gio');
  drawRow('02/11 09:30 - Phong Server A1', 'Nguoi la chua ro ID', 'Da chan - Lap bien ban ban giao P.CTSV');
  drawRow('01/11 16:45 - Kho Thiet bi C', 'Nhan vien bao tri', 'Bao nham - Da dang ky truoc');
  y += 10;

  // Sign-off
  const signY = Math.min(y + 10, 255);
  doc.setFont('helvetica', 'bold');
  doc.setFontSize(8.5);
  doc.setTextColor(0, 0, 0);
  doc.text('CAN BO LAP BAO CAO', margin + 20, signY, { align: 'center' });
  doc.text('BAN AN NINH & QUAN LY CO SO', pageWidth - margin - 35, signY, { align: 'center' });

  doc.setFont('helvetica', 'italic');
  doc.setFontSize(7.5);
  doc.setTextColor(100, 100, 100);
  doc.text('(Ky va ghi ro ho ten)', margin + 20, signY + 4, { align: 'center' });
  doc.text('(Phe duyet va luu tru)', pageWidth - margin - 35, signY + 4, { align: 'center' });

  doc.setFont('helvetica', 'bold');
  doc.setFontSize(8.5);
  doc.setTextColor(0, 0, 0);
  doc.text('Administrator', margin + 20, signY + 22, { align: 'center' });
  doc.text('Ban Quan Ly Co So Tan Uyen', pageWidth - margin - 35, signY + 22, { align: 'center' });

  return doc.output('blob');
}

