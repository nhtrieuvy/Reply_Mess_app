# DriveVoice · Giai đoạn 1

Ứng dụng Android Kotlin mở trực tiếp màn hình thiết lập quyền bằng tiếng Việt. Hỗ trợ cài đặt từ Android 8 (API 26) và nhắm đến Android 16 (API 36).

## Build và cài đặt

Mở thư mục dự án bằng Android Studio. Cài Android SDK Platform 36 và SDK Build-Tools 35.0.0, rồi chọn Gradle JDK 17 hoặc 21. Khi chạy từ terminal, đặt `ANDROID_HOME` trỏ tới Android SDK hoặc để Android Studio tạo `local.properties`.

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

Bật USB debugging, kết nối điện thoại khi đã dừng xe và chọn **Run** trong Android Studio. Có thể cài bản debug từ terminal nếu `adb` có trong `PATH`.

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Kiểm thử trên điện thoại

| Thao tác | Kết quả mong đợi |
| --- | --- |
| Mở DriveVoice sau khi cài mới | Màn hình nền đen hiển thị các bước. Hộp thoại cấp quyền chỉ mở khi người dùng chọn một bước. |
| Mở mục **Quyền truy cập thông báo**, bật DriveVoice và quay lại | Hiện đã cấp quyền. Khi Android kết nối listener, hàng này đổi thành **Dịch vụ đã kết nối**. |
| Từ chối quyền micrô rồi thử lại | Màn hình giữ trạng thái **Chưa cấp**. Nếu Android chặn hỏi lại, nút đổi sang **Mở cài đặt** và mở thông tin ứng dụng. |
| Cấp micrô, rồi mở lại ứng dụng sau khi thu hồi trong Cài đặt | Trạng thái cập nhật theo quyền hiện tại. Có thể kiểm tra thêm bằng lựa chọn **Chỉ lần này** trên Android 11 trở lên. |
| Mở mục **Tai nghe Bluetooth** | Android 12 trở lên yêu cầu quyền **Thiết bị ở gần** khi thiết bị hỗ trợ Bluetooth. Android 8–11 hiển thị trạng thái không cần cấp quyền riêng. Bước này kiểm tra quyền, có thể thực hiện trước khi ghép nối tai nghe. |
| Từ chối **Thông báo trạng thái** trên Android 13 trở lên | Quyền đọc thông báo và trạng thái cấp đủ quyền cốt lõi hoạt động độc lập với lựa chọn này. Trên Android 12 trở xuống, nếu đã tắt thông báo, nút mở trang cài đặt thông báo của ứng dụng. |
| Mở mục **Tối ưu hóa pin**, thay đổi lựa chọn rồi quay lại | Hàng pin phản ánh trạng thái Android báo cáo. Có thể hoàn tất các quyền cốt lõi với tối ưu hóa pin đang bật. |
| Tắt quyền truy cập thông báo trong Cài đặt rồi mở lại DriveVoice | Ứng dụng hiện **Chưa cấp** và trạng thái chung đổi thành **Chưa đủ quyền**. |

Nếu Android hiển thị **Chế độ cài đặt bị hạn chế** cho bản cài thử, mở thông tin ứng dụng DriveVoice và kiểm tra mục **Cho phép các chế độ cài đặt bị hạn chế** trong menu của hệ thống. [Trợ giúp Android](https://support.google.com/android/answer/12623953)

## Phạm vi bản build

- `app/build.gradle.kts` khai báo Compose Material 3, Hilt/KSP, Lifecycle, Coroutines và DataStore Preferences. Dữ liệu quyền được đọc trực tiếp từ Android. DataStore dành cho cấu hình ở giai đoạn sau.
- Listener chỉ theo dõi trạng thái kết nối với Android qua `StateFlow`. Bản build này chưa đọc nội dung, mở micrô, chạy foreground service hay gửi trả lời. Manifest không yêu cầu quyền `INTERNET` và không yêu cầu miễn tối ưu hóa pin trực tiếp.
- Quyền foreground service trong manifest chuẩn bị cho phát âm thanh, kết nối thiết bị và thu micrô. Khi triển khai dịch vụ, khai báo loại thực sự sử dụng và kiểm tra điều kiện khởi chạy. Android 14 trở lên giới hạn việc mở dịch vụ micrô từ nền ngay cả khi ứng dụng đã có `RECORD_AUDIO`. [Giới hạn khởi chạy foreground service của Android](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- Nhận diện giọng nói cần bộ nhận diện trên thiết bị hỗ trợ `vi-VN`. Trên thiết bị thiếu khả năng này, chế độ giọng nói phải báo không khả dụng. [API SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
