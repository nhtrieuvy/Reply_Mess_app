# DriveVoice 1.0.7

Android App Bundle cho Google Play, mã ứng dụng `com.drivevoice.app`, `versionCode 9`, `targetSdk 36`, `minSdk 26`. Bản release bật R8 và thu gọn tài nguyên. Kiểm tra mã ứng dụng trong Play Console trước lần đăng đầu tiên và tăng `versionCode` nếu số 9 đã được dùng.

## Đóng gói và ký

```powershell
.\gradlew.bat :app:bundleRelease :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

Trên thiết bị đã cài giọng đọc và mô hình nhận diện tiếng Việt ngoại tuyến, chạy thêm ca xác nhận bằng tín hiệu tổng hợp. Ca này đưa PCM vào bộ nhận diện và dùng tiếng gió mô phỏng trong cửa sổ xác nhận.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.drivevoicePcmDeviceTest=true
```

Gradle tạo `app/build/outputs/bundle/release/app-release.aab` và `app/build/outputs/mapping/release/mapping.txt`. Lưu bản mapping cùng số phiên bản để giải mã stack trace của bản đã phát hành.

Dùng **Build → Generate Signed Bundle / APK** trong Android Studio với upload key do chủ tài khoản quản lý. Để ký qua dòng lệnh, nạp bốn biến môi trường dưới đây từ môi trường bảo mật rồi chạy lại `:app:bundleRelease`.

| Biến | Giá trị |
| --- | --- |
| `DRIVEVOICE_UPLOAD_STORE` | Đường dẫn tuyệt đối đến keystore của upload key. |
| `DRIVEVOICE_UPLOAD_STORE_PASSWORD` | Mật khẩu keystore. |
| `DRIVEVOICE_UPLOAD_ALIAS` | Tên khóa dùng để tải lên Play. |
| `DRIVEVOICE_UPLOAD_KEY_PASSWORD` | Mật khẩu của khóa. |

Khi chưa cung cấp các biến này, Gradle tạo AAB chưa ký. Kiểm tra chữ ký của bản upload bằng `jarsigner -verify -certs app/build/outputs/bundle/release/app-release.aab` và đối chiếu chứng thư upload trong Play Console. Dùng Play App Signing cho bản đi đến thiết bị của người dùng. [Ký ứng dụng Android](https://developer.android.com/studio/publish/app-signing)

## Kiểm thử trên điện thoại trước khi mở Production

- [ ] Cài bản từ **Internal testing**. Thử trên Android cũ được hỗ trợ, Android 13 và Android 15 hoặc 16, với ít nhất một tai nghe Classic SCO và một tai nghe BLE nếu sản phẩm công bố hỗ trợ BLE.
- [ ] Nhận tin có dấu từ các phiên bản hiện hành của Zalo, Messenger và Telegram. Kiểm tra tin trực tiếp, tin nhóm có tên, nhóm thiếu tên, thông báo đã thu gọn, thông báo trùng và khóa màn hình. Chỉ xác nhận trả lời được khi ứng dụng nguồn cung cấp thao tác hợp lệ và cuộc trò chuyện có thể xác định.
- [ ] Khi thông báo bị xóa trong 4 giây gom tin, ứng dụng phải bỏ qua tin đó. Khi xóa lúc TTS đang đọc, lúc đọc lại bản nháp hoặc trước lệnh gửi, thử xác nhận lại và kiểm tra tài khoản nhận không có tin gửi ngoài ý muốn.
- [ ] Ngắt tai nghe đang chọn ở từng bước đọc, ghi âm và xác nhận. Kiểm tra phiên dừng, âm thanh nền được hoàn lại, bản nháp biến mất và không có tác vụ gửi mới. Trên Android 13 trở lên, đổi đầu ra sang loa trong lượt đọc và kiểm tra phát bị ngắt khi ứng dụng nhận ra tuyến đã đổi.
- [ ] Tắt mạng sau khi đã nhận tin, dùng giọng đọc và bộ nhận diện đã cài. So sánh với lúc thiếu dữ liệu ngôn ngữ. Khi ứng dụng nhắn tin chưa có mạng, DriveVoice chỉ báo đã chuyển trả lời nếu Android chấp nhận thao tác. Kiểm tra trạng thái phát đi trong chính ứng dụng nhắn tin.
- [ ] Thử **Gửi**, **Hủy**, **Đọc lại**, nút tai nghe, nhiễu gió và tắt micrô giữa lượt. Trên cấu hình đã xác minh PCM, tự gửi văn bản cần 4 giây liên tiếp không có giọng người sau lọc trong cửa sổ tối đa 8 giây, sau đó chờ kết quả nhận diện tối đa 700 ms. Thử lệnh **Hủy** nói nhỏ sát hạn. Lỗi nhận diện, mất focus hoặc thiếu bằng chứng im lặng đều bỏ bản nháp.
- [ ] Mở **Cài đặt → Kiểm tra âm thanh**. Thử thiết bị tích hợp, tai nghe Bluetooth có mic, thiết bị Bluetooth chỉ phát nhạc và thiết bị USB hoặc có dây nếu được liệt kê. Đối chiếu tuyến thực tế, thử loa riêng, thu đủ 5 giây và kết thúc sớm. Thử từ chối quyền, ngắt thiết bị, rời màn hình và mở mục kiểm tra khi Drive Mode đang chạy.
- [ ] Trên cả micrô điện thoại và Bluetooth, đọc một câu trả lời dài vài giây. Kiểm tra DriveVoice phát trọn bản nháp qua tuyến đã dùng để đọc tin, rồi mới báo micrô sẵn sàng xác nhận. Thử lệnh **Gửi** gần cuối cửa sổ bốn giây. Nếu lượt bị gián đoạn, dùng `adb logcat -d -s 'DriveVoiceAudio:W' '*:S'` để phân biệt lỗi tuyến phát với lỗi nhận diện. Các dòng chẩn đoán này không chứa nội dung tin hoặc bản nháp.
- [ ] Thử chế độ micrô điện thoại khi quyền Thiết bị ở gần bị từ chối, với tai nghe đã lưu đang ngắt và khi chỉ kết nối Bluetooth để phát nhạc. Thử lại với micrô tai nghe khả dụng, xác nhận nguồn thu thực tế và kiểm tra tự gửi trên cả hai nguồn.
- [ ] Thử tắt quyền thông báo, khởi động lại ứng dụng, tự bật từ nền trên OEM mục tiêu, ngắt tai nghe và hết 30 phút không xử lý tin. Kiểm tra **Dừng đọc** trên thông báo. Android hoặc OEM có thể yêu cầu mở ứng dụng để bắt đầu phiên.

## Nghiệm thu lọc tiếng ồn trên thiết bị thật

- [ ] Phát lại cùng mẫu lời nói và nhiễu ở vị trí mic cố định. So sánh bản gốc, RNNoise và một lượt thu riêng có bật NoiseSuppressor của Android nếu thiết bị hỗ trợ. Thử phòng yên, gió đều, gió giật, động cơ, còi và người nói bên cạnh. Ghi lại độ rõ của âm đầu, âm cuối và lỗi nhận ba lệnh theo từng thiết bị.
- [ ] Với mỗi cấu hình muốn bật lọc trong Drive Mode, chạy nhận diện vi-VN ngoại tuyến từ nguồn PCM có câu kiểm tra biết trước và xác minh bộ nhận diện không lấy một nguồn khác. So sánh kết quả theo từng mẫu và kiểm tra thời gian xác nhận.
- [ ] Với mỗi ứng dụng muốn bật gửi bản ghi, xác nhận thao tác trả lời trên thông báo thật hỗ trợ MIME âm thanh, chuyển vào đúng cuộc trò chuyện thử và nghe trên máy nhận. Ghi nhận trường hợp thông báo bị thu hồi, mất tuyến, lỗi tệp và hai lần bấm nút sát nhau. Chỉ mở sản xuất sau khi các ca lỗi đều không gửi ngoài ý muốn.

## Hồ sơ Play Console

- [ ] Xác minh tài khoản nhà phát triển và tính khả dụng của mã ứng dụng. Kiểm tra AAB đã ký, target API và hỗ trợ trang nhớ 16 KB. [Target API](https://developer.android.com/google/play/requirements/target-sdk) · [Trang nhớ 16 KB](https://developer.android.com/guide/practices/page-sizes)
- [ ] Đăng công khai [chính sách quyền riêng tư](app/src/main/assets/privacy-policy.html) dưới HTTPS, giữ truy cập tự do và bổ sung tên pháp nhân hoặc nhà phát triển cùng email hoặc kênh liên hệ thực tế. Điền URL đó trong Play Console. Ứng dụng có cùng chính sách trong **Cài đặt → Chính sách quyền riêng tư**. [Chính sách dữ liệu người dùng](https://support.google.com/googleplay/android-developer/answer/10144311)
- [ ] Điền **Data safety** theo bản nhị phân và luồng thực tế. Giải thích xử lý nội dung và âm thanh trên thiết bị, tên VIP và cấu hình chỉ lưu cục bộ, cùng thao tác chuyển bản nháp cho ứng dụng nhắn tin do người dùng yêu cầu. Rà chính sách của các thành phần được dùng và đối chiếu ngoại lệ chuyển dữ liệu do người dùng chủ động. [Hướng dẫn Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- [ ] Khai báo hai loại **foreground service** là `mediaPlayback` và `microphone`. Quay video bản test thể hiện hộp thoại giải thích quyền, bật phiên khi ứng dụng đang hiển thị, một lượt đọc và trả lời mẫu qua Bluetooth, thông báo đang chạy và thao tác dừng. Dùng tài khoản và tin thử trong video. [Khai báo foreground service](https://support.google.com/googleplay/android-developer/answer/16559646)
- [ ] Hoàn tất listing tiếng Việt, nội dung ứng dụng, độ tuổi, quảng cáo, hướng dẫn truy cập cho reviewer và thông tin hỗ trợ. Dùng [biểu tượng 512 × 512](store/play-icon.png), [feature graphic 1024 × 500](store/play-feature.png), [ảnh Drive Mode](store/phone-drive.png) và [ảnh Cài đặt](store/phone-settings.png). Ảnh điện thoại dùng dữ liệu thử. Thêm mô tả ảnh cho người đọc màn hình. [Yêu cầu hình ảnh Google Play](https://support.google.com/googleplay/android-developer/answer/9866151)
- [ ] Duyệt báo cáo **Pre-launch** và kết quả thử thiết bị thật trước Production. Với tài khoản cá nhân thuộc diện áp dụng, cần closed test tối thiểu 12 tester đăng ký tham gia liên tục 14 ngày rồi nộp yêu cầu Production access. [Yêu cầu closed testing](https://support.google.com/googleplay/android-developer/answer/14151465)

## Nội dung listing tiếng Việt

**Tên:** DriveVoice

**Mô tả ngắn:** Đọc và trả lời tin nhắn tiếng Việt bằng tai nghe Bluetooth.

**Mô tả:** DriveVoice đọc tin nhắn mới từ những ứng dụng bạn chọn. Dùng bộ lọc VIP để chỉ nghe các cuộc trò chuyện quan trọng. Khi tai nghe Bluetooth và nhận diện tiếng Việt ngoại tuyến khả dụng, bạn có thể đọc lại bản nháp và dùng lệnh giọng nói để xác nhận trả lời. Khả năng trả lời phụ thuộc vào thao tác mà ứng dụng nhắn tin gốc cung cấp.

**Ghi chú phiên bản:** Xác nhận tự gửi văn bản đánh giá giọng nói sau lọc trên thiết bị đã qua phép thử PCM tiếng Việt ngoại tuyến. Mục trả lời có thêm lựa chọn bản ghi âm cho cấu hình ứng dụng nhận đã được xác minh.
