-- ============================================================================
-- V125: Chuẩn hoá tiêu đề ngắn gọn, chuyên nghiệp cho Cấu hình hệ thống
--       và bổ sung nội dung hướng dẫn ngắn (tooltip) bằng tiếng Việt.
-- ============================================================================

ALTER TABLE system_configurations
    ADD COLUMN IF NOT EXISTS tooltip TEXT;

-- ACCESS_LEVEL
UPDATE system_configurations
SET description = 'Cấp truy cập mặc định — Người dùng thường',
    tooltip     = 'Mức truy cập được gán tự động khi tạo mới tài khoản người dùng phổ thông.'
WHERE config_key = 'ACCESS_LEVEL_DEFAULT_NORMAL_USER';

UPDATE system_configurations
SET description = 'Cấp truy cập mặc định — Bảo vệ',
    tooltip     = 'Mức truy cập được gán tự động khi tạo mới tài khoản nhân viên bảo vệ.'
WHERE config_key = 'ACCESS_LEVEL_DEFAULT_GUARD';

UPDATE system_configurations
SET description = 'Cấp truy cập mặc định — Quản lý cơ sở vật chất',
    tooltip     = 'Mức truy cập được gán tự động cho tài khoản Quản lý cơ sở vật chất.'
WHERE config_key = 'ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER';

UPDATE system_configurations
SET description = 'Cấp truy cập mặc định — Quản trị viên',
    tooltip     = 'Mức truy cập cao nhất được gán tự động khi tạo tài khoản Quản trị viên.'
WHERE config_key = 'ACCESS_LEVEL_DEFAULT_ADMIN';

-- ACCESS_REQUEST
UPDATE system_configurations
SET description = 'Số thành viên tối đa mỗi đơn nhóm',
    tooltip     = 'Giới hạn số thành viên được thêm vào một đơn xin truy cập theo nhóm (không tính người tạo đơn).'
WHERE config_key = 'ACCESS_REQUEST_MAX_GROUP_MEMBERS';

UPDATE system_configurations
SET description = 'Thời lượng tối đa mỗi lượt truy cập',
    tooltip     = 'Khoảng thời gian hiệu lực dài nhất được phép đăng ký cho một lượt ra vào khu vực.'
WHERE config_key = 'ACCESS_REQUEST_MAX_DURATION_HOURS';

UPDATE system_configurations
SET description = 'Số ngày được phép đăng ký trước tối đa',
    tooltip     = 'Người dùng chỉ có thể gửi đơn xin truy cập trước ngày bắt đầu tối đa trong giới hạn này.'
WHERE config_key = 'ACCESS_REQUEST_MAX_ADVANCE_DAYS';

UPDATE system_configurations
SET description = 'Thời gian trễ cho phép khi gửi đơn ngay',
    tooltip     = 'Cho phép thời điểm bắt đầu trong đơn được trễ hơn giờ hiện tại tối đa bấy nhiêu phút khi gửi yêu cầu.'
WHERE config_key = 'ACCESS_REQUEST_PAST_START_BUFFER_MINUTES';

UPDATE system_configurations
SET description = 'Cho phép đơn nhóm tại khu vực Bảo mật cao',
    tooltip     = 'Bật để cho phép người dùng tạo đơn đăng ký truy cập theo nhóm vào khu vực Bảo mật cao.'
WHERE config_key = 'ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE';

UPDATE system_configurations
SET description = 'Loại khu vực được bảo lãnh thành viên',
    tooltip     = 'Các loại khu vực cho phép người tạo đơn nhóm bảo lãnh thành viên chưa đủ cấp truy cập (ngăn cách bằng dấu phẩy).'
WHERE config_key = 'ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES';

UPDATE system_configurations
SET description = 'Mức chênh lệch cấp tối đa khi xin đơn cá nhân',
    tooltip     = 'Giới hạn độ chênh lệch cấp độ giữa cấp tài khoản hiện tại và khu vực được phép đăng ký trong đơn cá nhân.'
WHERE config_key = 'ACCESS_REQUEST_MAX_LEVEL_GAP';

-- ACCOUNT
UPDATE system_configurations
SET description = 'Số dòng tối đa mỗi tệp nhập tài khoản hàng loạt',
    tooltip     = 'Áp dụng cho cả chức năng nhập danh sách tài khoản người dùng phổ thông và cán bộ, bảo vệ.'
WHERE config_key = 'USER_BULK_IMPORT_MAX_ROWS';

-- AI_CONFIG
UPDATE system_configurations
SET description  = 'Ngưỡng độ tin cậy nhận diện khuôn mặt',
    tooltip      = 'Mức điểm tương đồng tối thiểu để hệ thống AI xác nhận khớp danh tính từ hình ảnh camera.',
    min_value    = 0.30,
    max_value    = 0.70,
    unit         = '(0.30 - 0.70)',
    config_value = CASE
        WHEN CAST(config_value AS NUMERIC) > 0.70 THEN '0.60'
        WHEN CAST(config_value AS NUMERIC) < 0.30 THEN '0.30'
        ELSE config_value
    END
WHERE config_key = 'AI_FACE_MATCH_THRESHOLD';

UPDATE system_configurations
SET description = 'Giờ bắt đầu khung ngoài giờ hoạt động',
    tooltip     = 'Mốc thời gian bắt đầu tính trạng thái ngoài giờ để hệ thống áp dụng cảnh báo xâm nhập ban đêm.'
WHERE config_key = 'AI_AFTER_HOUR_START';

UPDATE system_configurations
SET description = 'Giờ kết thúc khung ngoài giờ hoạt động',
    tooltip     = 'Mốc thời gian kết thúc ca đêm; sau giờ này khu vực trở về trạng thái vận hành bình thường.'
WHERE config_key = 'AI_AFTER_HOUR_END';

UPDATE system_configurations
SET description = 'Thời gian duy trì theo dõi khi khuất dấu',
    tooltip     = 'Khoảng thời gian AI tiếp tục giữ danh tính đối tượng khi bị khuất tầm nhìn hoặc quay lưng tạm thời.'
WHERE config_key = 'AI_TRACK_MAX_INACTIVE_SECONDS';

UPDATE system_configurations
SET description = 'Khoảng giãn cách cảnh báo người lạ lặp lại',
    tooltip     = 'Thời gian chờ tối thiểu trước khi phát cảnh báo mới cho cùng đối tượng chưa xác định trên một camera.'
WHERE config_key = 'AI_UNKNOWN_ALERT_COOLDOWN_SECONDS';

UPDATE system_configurations
SET description = 'Thời gian chờ phản hồi kiểm tra quyền từ AI',
    tooltip     = 'Thời gian tối đa thiết bị AI chờ máy chủ xác thực quyền truy cập trước khi chuyển sang xử lý dự phòng.'
WHERE config_key = 'AI_ACCESS_CHECK_TIMEOUT_SECONDS';

UPDATE system_configurations
SET description = 'Thời gian lưu tạm kết quả hợp lệ tại trạm AI',
    tooltip     = 'Thời lượng bộ nhớ đệm giữ kết quả cho phép vào nhằm tăng tốc độ nhận diện khi người đó đi qua liên tiếp.'
WHERE config_key = 'AI_ACCESS_DECISION_CACHE_TTL';

UPDATE system_configurations
SET description = 'Thời gian lưu tạm kết quả từ chối tại trạm AI',
    tooltip     = 'Thời lượng bộ nhớ đệm giữ quyết định từ chối để tránh gửi lặp lại yêu cầu kiểm tra quá dày.'
WHERE config_key = 'AI_ACCESS_DENY_CACHE_TTL';

-- GUEST
UPDATE system_configurations
SET description = 'Cấp truy cập tối thiểu của người bảo lãnh khách',
    tooltip     = 'Tài khoản phải đạt từ cấp truy cập này trở lên mới được phép đăng ký lượt khách tham quan.'
WHERE config_key = 'GUEST_HOST_MIN_LEVEL';

UPDATE system_configurations
SET description = 'Số lượng khách tối đa mỗi lượt đăng ký',
    tooltip     = 'Giới hạn tối đa số khách đi cùng trong một hồ sơ đăng ký tiếp khách.'
WHERE config_key = 'GUEST_MAX_PER_VISIT';

UPDATE system_configurations
SET description = 'Thời lượng tối đa mỗi lượt khách ghé thăm',
    tooltip     = 'Tổng thời gian hiệu lực dài nhất cho phép đối với một lượt đăng ký khách.'
WHERE config_key = 'GUEST_VISIT_MAX_HOURS';

UPDATE system_configurations
SET description = 'Số ngày tối đa được đặt lịch khách trước',
    tooltip     = 'Người bảo lãnh chỉ được tạo lịch đón khách trước ngày hẹn tối đa trong số ngày này.'
WHERE config_key = 'GUEST_MAX_ADVANCE_DAYS';

UPDATE system_configurations
SET description = 'Thời gian lưu dữ liệu nhận diện sau khi khách rời',
    tooltip     = 'Ảnh và đặc trưng khuôn mặt của khách sẽ được tự động xóa sau khoảng thời gian này kể từ khi kết thúc lượt.'
WHERE config_key = 'GUEST_FACE_RETENTION_HOURS';

UPDATE system_configurations
SET description = 'Thời hạn ẩn danh họ tên khách sau khi kết thúc',
    tooltip     = 'Sau số ngày này kể từ lúc lượt khách kết thúc, hệ thống sẽ tự động ẩn danh thông tin họ tên.'
WHERE config_key = 'GUEST_RECORD_RETENTION_DAYS';

UPDATE system_configurations
SET description = 'Thời hạn hiệu lực liên kết xem ảnh khách',
    tooltip     = 'Mỗi liên kết xem ảnh chân dung khách chỉ có hiệu lực bảo mật trong khoảng thời gian này.'
WHERE config_key = 'GUEST_PHOTO_URL_TTL_SECONDS';

UPDATE system_configurations
SET description = 'Phiên bản biểu mẫu chấp thuận ghi hình khách',
    tooltip     = 'Mã phiên bản thông báo quyền riêng tư đang áp dụng cho quy trình tiếp nhận ảnh khách.'
WHERE config_key = 'GUEST_CONSENT_NOTICE_VERSION';

UPDATE system_configurations
SET description = 'Thời gian nhắc bổ sung ảnh trước giờ khách đến',
    tooltip     = 'Hệ thống nhắc Quản trị viên nếu lượt khách đã duyệt sắp đến giờ hẹn nhưng vẫn thiếu ảnh chân dung.'
WHERE config_key = 'GUEST_PHOTO_REMINDER_MINUTES_BEFORE';

UPDATE system_configurations
SET description = 'Độ dài tối thiểu của mục đích tiếp khách',
    tooltip     = 'Số ký tự tối thiểu người tạo phải nhập cho lý do đón khách (không tính khoảng trắng hai đầu).'
WHERE config_key = 'GUEST_PURPOSE_MIN_LENGTH';

UPDATE system_configurations
SET description = 'Độ dài tối đa của mục đích tiếp khách',
    tooltip     = 'Giới hạn số ký tự tối đa cho phần mô tả mục đích lượt khách, không vượt quá 500 ký tự.'
WHERE config_key = 'GUEST_PURPOSE_MAX_LENGTH';

-- NOTIFICATION
UPDATE system_configurations
SET description = 'Ngưỡng cảnh báo đơn chờ duyệt quá hạn',
    tooltip     = 'Đơn xin truy cập ở trạng thái chờ duyệt vượt quá số giờ này sẽ gửi thông báo nhắc Quản lý cơ sở vật chất.'
WHERE config_key = 'NOTIFICATION_PENDING_OVERDUE_HOURS';

UPDATE system_configurations
SET description = 'Thời gian nhắc trước khi lượt đã duyệt bắt đầu',
    tooltip     = 'Gửi thông báo nhắc nhở người đăng ký trước thời điểm khung giờ ra vào có hiệu lực.'
WHERE config_key = 'NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES';

UPDATE system_configurations
SET description = 'Chu kỳ kiểm tra các lượt sắp đến giờ hiệu lực',
    tooltip     = 'Tần suất hệ thống rà soát các đơn đã duyệt sắp bắt đầu để gửi nhắc nhở tự động.'
WHERE config_key = 'NOTIFICATION_STARTING_SOON_SCAN_MINUTES';

UPDATE system_configurations
SET description = 'Chu kỳ kiểm tra đơn chờ duyệt tồn đọng',
    tooltip     = 'Tần suất rà soát và báo cáo các đơn chờ duyệt quá hạn cho Quản lý cơ sở vật chất.'
WHERE config_key = 'NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES';

-- SECURITY
UPDATE system_configurations
SET description = 'Giới hạn tra cứu thông tin thành viên mỗi phút',
    tooltip     = 'Số lần tối đa một tài khoản được phép tìm kiếm thành viên trong 1 phút nhằm hạn chế dò quét dữ liệu.'
WHERE config_key = 'SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE';

UPDATE system_configurations
SET description = 'Thời lượng tối thiểu mỗi phiên chế độ sự kiện',
    tooltip     = 'Mỗi lần kích hoạt chế độ mở rộng sự kiện cần kéo dài tối thiểu bằng khoảng thời gian này.'
WHERE config_key = 'EVENT_MODE_MIN_MINUTES';

UPDATE system_configurations
SET description = 'Thời gian nhắc trước khi phiên sự kiện kết thúc',
    tooltip     = 'Gửi thông báo trước khi chế độ sự kiện hết hạn để người phụ trách gia hạn kịp thời (nhập 0 để tắt).'
WHERE config_key = 'EVENT_MODE_EXPIRY_REMINDER_MINUTES';

UPDATE system_configurations
SET description = 'Số ngày đặt lịch sự kiện trước tối đa',
    tooltip     = 'Giới hạn khoảng thời gian tối đa cho phép lên lịch chế độ sự kiện trước ngày diễn ra.'
WHERE config_key = 'EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS';

UPDATE system_configurations
SET description = 'Thời gian nhắc quản lý trước lịch sự kiện',
    tooltip     = 'Gửi thông báo cho người quản lý trước khi lịch sự kiện tự động kích hoạt (nhập 0 để tắt).'
WHERE config_key = 'EVENT_MODE_SCHEDULE_REMINDER_MINUTES';

UPDATE system_configurations
SET description = 'Thời gian gộp lượt ghi nhật ký ra vào trùng lặp',
    tooltip     = 'Cùng một người đi qua cùng một camera trong khoảng thời gian này chỉ được ghi nhận một lượt.'
WHERE config_key = 'ENTRY_LOG_DEBOUNCE_SECONDS';

UPDATE system_configurations
SET description = 'Thời gian lưu trữ nhật ký ra vào tại camera',
    tooltip     = 'Dữ liệu nhật ký ra vào cũ hơn số ngày này sẽ được tác vụ dọn dẹp hằng ngày tự động xóa.'
WHERE config_key = 'ENTRY_LOG_RETENTION_DAYS';

UPDATE system_configurations
SET description = 'Thời hạn vào khu vực của bảo vệ nhận sự cố',
    tooltip     = 'Bảo vệ sau khi tiếp nhận sự cố được cấp quyền tạm thời vào khu vực tương ứng trong thời gian này.'
WHERE config_key = 'GUARD_INCIDENT_ACCESS_MINUTES';

UPDATE system_configurations
SET description = 'Thời gian ân hạn rời khu vực sau khi xử lý sự cố',
    tooltip     = 'Sau khi đóng sự cố, bảo vệ vẫn được đi qua camera khu vực thêm thời gian này để rời khỏi khu vực.'
WHERE config_key = 'GUARD_INCIDENT_EXIT_GRACE_MINUTES';

UPDATE system_configurations
SET description = 'Thời gian chờ trước khi báo cáo sự cố quá hạn',
    tooltip     = 'Nếu chưa có bảo vệ tiếp nhận trong thời gian này, sự cố sẽ được chuyển cấp lên Quản lý cơ sở vật chất.'
WHERE config_key = 'INCIDENT_ESCALATE_FM_MINUTES';

UPDATE system_configurations
SET description = 'Chu kỳ kiểm tra sự cố cần chuyển cấp quản lý',
    tooltip     = 'Tần suất hệ thống rà soát các sự cố chưa có người nhận để thực hiện báo cáo vượt cấp ngay lập tức.'
WHERE config_key = 'INCIDENT_ESCALATION_CHECK_SECONDS';

UPDATE system_configurations
SET description = 'Độ dài tối thiểu của lý do thao tác nghiệp vụ',
    tooltip     = 'Áp dụng cho lý do hủy, từ chối, hoàn thành đơn, thay đổi quy tắc hoặc cập nhật cấu hình.'
WHERE config_key = 'REASON_MIN_LENGTH';

UPDATE system_configurations
SET description = 'Độ dài tối đa của lý do thao tác nghiệp vụ',
    tooltip     = 'Giới hạn ký tự tối đa cho nội dung lý do nhập vào (không vượt quá 500 ký tự).'
WHERE config_key = 'REASON_MAX_LENGTH';

UPDATE system_configurations
SET description = 'Thời hạn hiệu lực liên kết đặt lại mật khẩu',
    tooltip     = 'Liên kết khôi phục mật khẩu gửi qua email sẽ hết hiệu lực sau khoảng thời gian này kể từ lúc gửi.'
WHERE config_key = 'PASSWORD_RESET_TOKEN_MINUTES';

UPDATE system_configurations
SET description = 'Bật điều phối mời bảo vệ nhận sự cố theo đợt',
    tooltip     = 'Khi bật, hệ thống ưu tiên mời bảo vệ theo từng đợt phù hợp trước khi phát cảnh báo toàn đội.'
WHERE config_key = 'DISPATCH_ENABLED';

UPDATE system_configurations
SET description = 'Khung thời gian tính tải công việc bảo vệ',
    tooltip     = 'Đếm số sự cố bảo vệ đã tiếp nhận trong khoảng thời gian gần nhất để xếp hạng ưu tiên mời.'
WHERE config_key = 'DISPATCH_LOAD_WINDOW_MINUTES';

UPDATE system_configurations
SET description = 'Chu kỳ kiểm tra hết hạn lời mời nhận sự cố',
    tooltip     = 'Tần suất kiểm tra các lượt mời đã hết giờ phản hồi để chuyển sang bảo vệ tiếp theo hoặc phát toàn đội.'
WHERE config_key = 'DISPATCH_CHECK_SECONDS';

UPDATE system_configurations
SET description = 'Mức độ mặc định khi tạo sự cố an ninh',
    tooltip     = 'Mức độ được gán cho sự cố nếu không khớp quy tắc riêng nào (Trung bình, Cao hoặc Khẩn cấp).'
WHERE config_key = 'INCIDENT_SEVERITY_DEFAULT';

UPDATE system_configurations
SET description = 'Số lần đăng nhập sai tối đa trước khi khóa',
    tooltip     = 'Tài khoản sẽ bị tạm khóa nếu nhập sai mật khẩu liên tiếp chạm ngưỡng này trong khung đếm.'
WHERE config_key = 'LOGIN_MAX_FAILED_ATTEMPTS';

UPDATE system_configurations
SET description = 'Khung thời gian đếm số lần đăng nhập sai',
    tooltip     = 'Hệ thống đếm dồn số lượt nhập sai mật khẩu của cùng một email trong khoảng thời gian này.'
WHERE config_key = 'LOGIN_FAILED_WINDOW_MINUTES';

UPDATE system_configurations
SET description = 'Thời gian tạm khóa tài khoản khi đăng nhập sai',
    tooltip     = 'Trong thời gian bị khóa, mọi yêu cầu đăng nhập vào tài khoản đều bị từ chối tạm thời.'
WHERE config_key = 'LOGIN_LOCK_MINUTES';

UPDATE system_configurations
SET description = 'Giới hạn tần suất đăng nhập từ mỗi địa chỉ IP',
    tooltip     = 'Số yêu cầu đăng nhập và khôi phục mật khẩu tối đa từ cùng một IP trong mỗi phút để chống dò mật khẩu.'
WHERE config_key = 'LOGIN_IP_RATE_PER_MINUTE';

UPDATE system_configurations
SET description = 'Độ dài tối thiểu của mật khẩu mới',
    tooltip     = 'Mật khẩu do người dùng đặt cần đạt số ký tự tối thiểu này, kèm ít nhất 1 chữ cái và 1 chữ số.'
WHERE config_key = 'PASSWORD_MIN_LENGTH';

UPDATE system_configurations
SET description = 'Thời hạn liên kết phát lại video ghi hình',
    tooltip     = 'Liên kết bảo mật để phát hoặc tải xuống một đoạn video ghi hình có hiệu lực trong thời gian này.'
WHERE config_key = 'RECORDING_STREAM_TOKEN_SECONDS';

UPDATE system_configurations
SET description = 'Thời hạn liên kết xem luồng trực tiếp có nhận diện',
    tooltip     = 'Liên kết bảo mật mở luồng trực tiếp kèm khung nhận diện AI trên camera có hiệu lực trong thời gian này.'
WHERE config_key = 'ANNOTATED_STREAM_TOKEN_SECONDS';

UPDATE system_configurations
SET description = 'Tốc độ đi bộ ước tính trên bản đồ tuần tra',
    tooltip     = 'Dùng để tính thời gian di chuyển dự kiến cho tuyến tuần tra hoặc điều hướng đến vị trí sự cố.'
WHERE config_key = 'WALKING_SPEED_MPS';

UPDATE system_configurations
SET description = 'Thời gian cộng thêm cho mỗi lần chuyển tầng',
    tooltip     = 'Khoảng thời gian ước tính cộng vào tổng lộ trình mỗi khi người dùng cần di chuyển qua cầu thang giữa các tầng.'
WHERE config_key = 'FLOOR_CHANGE_SECONDS';
