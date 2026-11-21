# DriveVoice · Giai đoạn 2

Ứng dụng Android Kotlin đọc tin nhắn mới bằng tiếng Việt từ Zalo, Messenger và Telegram khi người dùng bật phiên đọc. Hỗ trợ cài đặt từ Android 8 (API 26) và nhắm đến Android 16 (API 36).

## Build và cài đặt

Mở thư mục dự án bằng Android Studio. Cài Android SDK Platform 36 và SDK Build-Tools 35.0.0, rồi chọn Gradle JDK 17 hoặc 21. Khi chạy từ terminal, đặt `ANDROID_HOME` trỏ tới Android SDK hoặc để Android Studio tạo `local.properties`.

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Bật USB debugging, kết nối điện thoại khi đã dừng xe và chọn **Run** trong Android Studio. Có thể cài bản debug từ terminal nếu `adb` có trong `PATH`.

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Kiểm thử phiên đọc trên điện thoại

1. Trong cài đặt **Chuyển văn bản thành giọng nói** của Android, tải giọng **Tiếng Việt (Việt Nam)** để dùng ngoại tuyến. Kết nối tai nghe nếu muốn nghe qua Bluetooth và xác nhận tai nghe đang là đầu ra phương tiện.
2. Cấp **Quyền truy cập thông báo** cho DriveVoice. Khi thấy **Dịch vụ đã kết nối**, chọn **Bắt đầu đọc tin nhắn** và đợi trạng thái **Đang chờ tin nhắn mới**.
3. Dùng một tài khoản hoặc điện thoại khác gửi tin nhắn có dấu vào từng ứng dụng nguồn. Cho phép ứng dụng nguồn hiện bản xem trước. Sau khoảng 4 giây, DriveVoice đọc theo mẫu “Tin nhắn từ An trên Zalo…”.

| Thao tác | Kết quả mong đợi |
| --- | --- |
| Gửi hai tin ngắn trong 4 giây từ cùng một người trong một cuộc trò chuyện | Một lượt đọc, giữ thứ tự nội dung. Cửa sổ 4 giây tính lại từ tin ngắn mới nhất trong nhóm. |
| Gửi xen kẽ từ hai người hoặc từ hai cuộc trò chuyện | Các nhóm đọc độc lập và phát tuần tự. |
| Bật phiên đọc khi đã có thông báo tin nhắn, rồi gửi thêm một tin | Chỉ đọc nội dung mới mà ứng dụng nguồn cung cấp. |
| Gọi thử qua Messenger, hoặc tắt bản xem trước để nhận thông báo chung chung | Bỏ qua cuộc gọi, thông báo tổng hợp hoặc nội dung thay thế đã nhận diện. |
| Phát nhạc, đưa DriveVoice xuống nền hoặc khóa màn hình, rồi gửi tin | Phiên đọc vẫn hoạt động. Android yêu cầu nhạc nền giảm âm lượng khi có hỗ trợ audio ducking và khôi phục sau lượt đọc. |
| Chọn **Dừng đọc** khi còn tin đang chờ hoặc trong khi phát | Dừng phát, trả audio focus và xóa hàng đợi. Có thể dừng từ thông báo trạng thái nếu đã cấp quyền hiển thị thông báo. |
| Khởi động phiên trên thiết bị thiếu giọng tiếng Việt ngoại tuyến | Hiển thị hướng dẫn tải giọng và kết thúc phiên. |

Để kiểm tra TTS khi điện thoại không có mạng, nhận tin trước rồi bật chế độ máy bay trong cửa sổ 4 giây. Có thể dùng loa điện thoại cho bước này vì chế độ máy bay có thể ngắt tai nghe. Kiểm thử tự động cho bộ lọc và cập nhật thông báo dùng dữ liệu giả lập và chạy qua lệnh sau khi ADB thấy thiết bị Android.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Kiểm thử quyền hệ thống

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
- Phiên đọc bắt đầu bằng thao tác của người dùng và chạy trong foreground service loại `mediaPlayback`. Android 15 trở lên yêu cầu ứng dụng nhắm API 35 trở lên phải ở tiền cảnh hoặc chạy foreground service thì mới xin được audio focus. [Audio focus trên Android](https://developer.android.com/media/optimize/audio-focus)
- Bộ lọc ưu tiên `MessagingStyle`. Nếu ứng dụng nguồn chỉ cung cấp tiêu đề và nội dung, DriveVoice dùng hai trường đó và loại các mẫu tổng hợp hoặc đang đồng bộ đã biết. Định dạng, nội dung bị ẩn và khả năng nhận diện người gửi còn tùy phiên bản của ứng dụng nguồn.
- Tin nhắn, dấu vết chống đọc lặp và hàng đợi chỉ tồn tại trong RAM. Mỗi tin được giới hạn 350 ký tự, mỗi nhóm tối đa 6 tin và khoảng 700 ký tự nội dung. Khi hàng đợi đầy, nhóm cũ nhất đang chờ bị loại để giới hạn bộ nhớ. Thông báo của DriveVoice chỉ hiện trạng thái phiên đọc.
- DriveVoice yêu cầu engine TTS của Google và chỉ gửi văn bản khi Android báo giọng tiếng Việt đã cài và hoạt động ngoại tuyến. Android quản lý đầu ra âm thanh. Manifest không yêu cầu quyền `INTERNET` hay quyền miễn tối ưu hóa pin trực tiếp. [API TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- Nhận diện giọng nói, gửi trả lời, định tuyến micrô Bluetooth và kích hoạt theo tai nghe thuộc các giai đoạn sau. Android 14 trở lên giới hạn việc mở dịch vụ micrô từ nền ngay cả khi ứng dụng đã có `RECORD_AUDIO`. [Giới hạn khởi chạy foreground service của Android](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- Nhận diện giọng nói cần bộ nhận diện trên thiết bị hỗ trợ `vi-VN`. Trên thiết bị thiếu khả năng này, chế độ giọng nói phải báo không khả dụng. [API SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)
