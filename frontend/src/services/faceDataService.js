const API_BASE_URL = import.meta.env.VITE_BACKEND_URL || 'http://localhost:8080/api/v1/face-data';

function getAuthHeaders() {
  const token = localStorage.getItem('accessToken');
  const headers = {};
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }
  return headers;
}

export const faceDataService = {
  /**
   * Lấy danh sách hồ sơ khuôn mặt có phân trang & tìm kiếm
   */
  async getFaceList(keyword = '', page = 0, size = 10) {
    const params = new URLSearchParams({
      page: page.toString(),
      size: size.toString(),
    });
    if (keyword && keyword.trim() !== '') {
      params.append('keyword', keyword.trim());
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 6000);

    try {
      const response = await fetch(`${API_BASE_URL}?${params.toString()}`, {
        headers: getAuthHeaders(),
        signal: controller.signal,
      });
      clearTimeout(timeoutId);

      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      return await response.json();
    } catch (err) {
      clearTimeout(timeoutId);
      throw err;
    }
  },

  /**
   * Đăng ký hồ sơ khuôn mặt đơn lẻ (Mã số, Họ tên, 3 file ảnh)
   */
  async registerSingleFace(code, fullName, frontFile, leftFile, rightFile) {
    const formData = new FormData();
    formData.append('code', code);
    formData.append('fullName', fullName);
    formData.append('frontImage', frontFile);
    formData.append('leftImage', leftFile);
    formData.append('rightImage', rightFile);

    const response = await fetch(`${API_BASE_URL}/register`, {
      method: 'POST',
      headers: getAuthHeaders(),
      body: formData,
    });

    if (!response.ok) {
      const errText = await response.text();
      throw new Error(errText || 'Đăng ký khuôn mặt thất bại.');
    }
    return await response.json();
  },

  /**
   * Nạp hàng loạt hồ sơ từ file ZIP
   */
  async bulkImportZip(zipFile) {
    const formData = new FormData();
    formData.append('file', zipFile);

    const response = await fetch(`${API_BASE_URL}/bulk-import`, {
      method: 'POST',
      headers: getAuthHeaders(),
      body: formData,
    });

    if (!response.ok) {
      const errText = await response.text();
      throw new Error(errText || 'Nạp hàng loạt thất bại.');
    }
    return await response.json();
  },

  /**
   * Xóa hồ sơ khuôn mặt theo ID
   */
  async deleteFace(id) {
    const response = await fetch(`${API_BASE_URL}/${id}`, {
      method: 'DELETE',
      headers: getAuthHeaders(),
    });
    if (!response.ok) {
      throw new Error(`Lỗi khi xóa hồ sơ: ${response.statusText}`);
    }
    return true;
  }
};
