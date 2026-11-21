# DriveVoice · Giai đoạn 4

Ứng dụng Android Kotlin đọc tin nhắn mới bằng tiếng Việt từ Zalo, Messenger và Telegram. Tính năng trả lời rảnh tay dùng tai nghe Bluetooth, nhận diện tiếng Việt trên thiết bị và thao tác trả lời của thông báo gốc. Chế độ đọc hỗ trợ Android 8 trở lên, chế độ trả lời yêu cầu Android 12 trở lên.

## Drive Mode và cài đặt

Màn hình chính dùng nền OLED đen, Space Grotesk và một màu nhấn theo trạng thái. Orb lấy biên độ từ PCM16 của TTS và mức RMS của bộ nhận diện. Nhịp đồ họa TTS chạy theo mốc bắt đầu phát mà engine báo về.

- Chạm vùng dưới để **BẮT ĐẦU**, **DỪNG NGAY** khi đang xử lý tin hoặc **ĐỌC LẠI** tin vừa đọc trong cùng phiên. Đọc lại qua màn hình chỉ phát lại lời đọc. Có thể dừng phiên từ Cài đặt hoặc thông báo trạng thái.
- Trong **Cài đặt**, chọn ứng dụng nguồn, tai nghe đã ghép nối, chế độ trả lời và danh sách VIP. Bộ lọc VIP so khớp tên hiển thị theo từng ứng dụng. So khớp giữ dấu tiếng Việt và bỏ qua khác biệt hoa thường hoặc khoảng trắng lặp. Hai người trùng tên hiển thị trong cùng ứng dụng sẽ cùng khớp.
- DataStore lưu lựa chọn của người dùng. Nội dung tin, tên người gửi vừa đọc, bản nháp và quyền trả lời tạm thời nằm trong RAM. Thay đổi nguồn hoặc VIP áp dụng cho tin tiếp theo và tin còn trong hàng đợi. Lựa chọn tai nghe và cấu hình micrô áp dụng khi bắt đầu phiên mới.
- Với **Tự bật khi kết nối thiết bị**, DriveVoice chờ tuyến âm thanh của tai nghe đã chọn rồi bật phiên đọc. Ngắt tai nghe sẽ dừng phiên gắn với thiết bị. Nếu hệ thống chặn mở dịch vụ từ nền, DriveVoice dùng thông báo mời mở ứng dụng khi đã được cấp quyền hiển thị thông báo. Khi ở nền, tự bật chỉ cho phép đọc. Muốn dùng micrô, bắt đầu phiên mới khi DriveVoice đang hiển thị. Android 9–12L cần chọn tai nghe làm đầu ra phương tiện trong hệ thống. [Giới hạn dịch vụ nền Android](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

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
2. Từ Home, chọn **CẤP QUYỀN** hoặc **Cài đặt → Thiết lập quyền**. Bật quyền truy cập thông báo rồi quay về Home. Chọn **BẮT ĐẦU** và đợi trạng thái **SẴN SÀNG**.
3. Dùng một tài khoản hoặc điện thoại khác gửi tin nhắn có dấu vào từng ứng dụng nguồn. Cho phép ứng dụng nguồn hiện bản xem trước. Sau khoảng 4 giây, DriveVoice đọc theo mẫu “Tin nhắn từ An trên Zalo…”.

| Thao tác | Kết quả mong đợi |
| --- | --- |
| Gửi hai tin ngắn trong 4 giây từ cùng một người trong một cuộc trò chuyện | Một lượt đọc, giữ thứ tự nội dung. Cửa sổ 4 giây tính lại từ tin ngắn mới nhất trong nhóm. |
| Gửi xen kẽ từ hai người hoặc từ hai cuộc trò chuyện | Các nhóm đọc độc lập và phát tuần tự. |
| Bật phiên đọc khi đã có thông báo tin nhắn, rồi gửi thêm một tin | Chỉ đọc nội dung mới mà ứng dụng nguồn cung cấp. |
| Gọi thử qua Messenger, hoặc tắt bản xem trước để nhận thông báo chung chung | Bỏ qua cuộc gọi, thông báo tổng hợp hoặc nội dung thay thế đã nhận diện. |
| Phát nhạc, đưa DriveVoice xuống nền hoặc khóa màn hình, rồi gửi tin | Phiên đọc vẫn hoạt động. Android yêu cầu nhạc nền giảm âm lượng khi có hỗ trợ audio ducking và khôi phục sau lượt đọc. |
| Chọn **DỪNG NGAY** trong lúc phát | Dừng phát, trả audio focus và xóa hàng đợi. Khi muốn dừng phiên đang chờ, dùng **Dừng đọc** trong Cài đặt hoặc thông báo trạng thái. |
| Khởi động phiên trên thiết bị thiếu giọng tiếng Việt ngoại tuyến | Hiển thị hướng dẫn tải giọng và kết thúc phiên. |

Để kiểm tra TTS khi điện thoại không có mạng, nhận tin trước rồi bật chế độ máy bay trong cửa sổ 4 giây. Có thể dùng loa điện thoại cho bước này vì chế độ máy bay có thể ngắt tai nghe. Kiểm thử tự động cho bộ lọc và cập nhật thông báo dùng dữ liệu giả lập và chạy qua lệnh sau khi ADB thấy thiết bị Android.

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

## Kiểm thử trả lời rảnh tay trên điện thoại

1. Khi đã dừng xe, ghép nối một tai nghe có micrô Bluetooth SCO hoặc LE Audio và bật cả âm thanh phương tiện lẫn âm thanh cuộc gọi cho tai nghe. Cài dữ liệu **Tiếng Việt (Việt Nam)** cho cả bộ đọc TTS và bộ nhận diện giọng nói ngoại tuyến của thiết bị. Đây là hai gói dữ liệu riêng.
2. Cấp quyền **Micrô**, **Thiết bị ở gần** và **Quyền truy cập thông báo**. Trong **Cài đặt**, bật **Trả lời rảnh tay bằng tai nghe Bluetooth** và giữ tùy chọn tự gửi tắt. Quay về Home rồi chọn **BẮT ĐẦU**.
3. Dùng tài khoản thử gửi một tin có hiện nút **Trả lời** trên thông báo của ứng dụng nguồn. Khi nghe tiếng bíp sau lượt đọc, nói một câu ngắn. DriveVoice chờ tối đa 6 giây để nhận bản nháp.
4. Đợi DriveVoice đọc tên cuộc trò chuyện và toàn bộ bản nháp. Sau tiếng bíp tiếp theo, nói **Gửi** trong 4 giây. Kiểm tra ứng dụng nhắn tin gốc và thiết bị của người nhận để xác nhận đã gửi. Tiếng xác nhận và trạng thái DriveVoice báo rằng thao tác đã được chuyển cho ứng dụng nguồn.

| Tình huống | Kết quả mong đợi |
| --- | --- |
| Nói **Đọc lại** hoặc bấm Play/Pause trong cửa sổ 6 giây | Đọc lại tin đến một lần rồi mở lại cửa sổ trả lời. |
| Nói **Đọc lại** trong cửa sổ xác nhận | Đọc lại bản nháp một lần rồi xin xác nhận lại. |
| Bấm Play/Pause hoặc nút nhận cuộc gọi tương ứng trong cửa sổ xác nhận | Xác nhận gửi nếu Android chuyển phím đến phiên media của DriveVoice. Khả năng nhận phím thay đổi theo tai nghe và trình phát đang hoạt động. |
| Nói **Hủy**, nói một câu khác, hoặc im lặng khi tắt tự gửi | Kết thúc lượt trả lời và bỏ bản nháp. |
| Bật rõ tùy chọn tự gửi trước khi bắt đầu phiên, đọc bản nháp rồi giữ im lặng trong bước xác nhận | Gửi sau 4 giây nếu micrô đã sẵn sàng, không phát hiện tiếng nói, tai nghe còn kết nối và thao tác trả lời còn hiệu lực. Nếu báo lỗi nhận diện, DriveVoice bỏ bản nháp. |
| Xóa thông báo gốc trên điện thoại khác đồng bộ cùng tài khoản hoặc nhờ người hỗ trợ xóa ngay trên máy thử trước khi xác nhận | Nếu Android đã thu hồi thông báo, DriveVoice báo không thể trả lời. |
| Thử một thông báo thiếu ô trả lời, thiếu tên nhóm hoặc thiết bị thiếu mô hình nhận diện ngoại tuyến | Phiên vẫn có thể đọc tin. DriveVoice bỏ qua bước ghi bản nháp khi thông báo thiếu thao tác trả lời phù hợp. |
| Ngắt tai nghe trong lúc ghi hoặc xác nhận | Hủy nhận diện hoặc từ chối gửi. |

Kiểm tra riêng cả Zalo, Messenger và Telegram. Mỗi ứng dụng quyết định có cung cấp thao tác trả lời theo phiên bản, tài khoản, loại cuộc trò chuyện và trạng thái khóa hay không. [Trả lời trực tiếp từ thông báo Android](https://developer.android.com/develop/ui/views/notifications/build-notification#reply-action)

## Kiểm thử cài đặt và tự kích hoạt

| Thao tác | Kết quả mong đợi |
| --- | --- |
| Tắt một ứng dụng nguồn, khởi động lại DriveVoice và gửi tin thử từ ứng dụng đó | Công tắc vẫn tắt. Tin thử không được đọc. |
| Thêm VIP cho một ứng dụng rồi bật **Chỉ đọc tin nhắn từ VIP** | Đọc tin từ tên khớp trong đúng ứng dụng. Bỏ qua người cùng tên ở ứng dụng khác và người ngoài danh sách. |
| Chọn tai nghe đã ghép nối, bật tự kích hoạt rồi ngắt và kết nối lại | Phiên đọc gắn với tai nghe tự bật nếu Android cho phép. Thiết bị khác không kích hoạt phiên. |
| Ngắt tai nghe trong lúc phát, sau đó kết nối lại | Phiên gắn với thiết bị dừng trong lần ngắt. Lần kết nối mới có thể bật một phiên mới. |
| Dừng thủ công, giữ tai nghe kết nối rồi mở lại Home | Phiên vẫn dừng trong kết nối hiện tại. |
| Gửi một tin, đợi đọc xong rồi chạm **ĐỌC LẠI** | Home phát lại tin đã đọc. Khi đang phát, vùng chạm đổi thành **DỪNG NGAY**. |

## Kiểm thử quyền hệ thống

| Thao tác | Kết quả mong đợi |
| --- | --- |
| Mở DriveVoice sau khi cài mới rồi chọn **CẤP QUYỀN** | Home dẫn đến màn hình thiết lập. Hộp thoại hệ thống mở khi chọn một bước. |
| Mở mục **Quyền truy cập thông báo**, bật DriveVoice và quay lại | Hiện đã cấp quyền. Khi Android kết nối listener, hàng này đổi thành **Dịch vụ đã kết nối**. |
| Từ chối quyền micrô rồi thử lại | Màn hình giữ trạng thái **Chưa cấp**. Nếu Android chặn hỏi lại, nút đổi sang **Mở cài đặt** và mở thông tin ứng dụng. |
| Cấp micrô, rồi mở lại ứng dụng sau khi thu hồi trong Cài đặt | Trạng thái cập nhật theo quyền hiện tại. Có thể kiểm tra thêm bằng lựa chọn **Chỉ lần này** trên Android 11 trở lên. |
| Mở mục **Tai nghe Bluetooth** | Android 12 trở lên yêu cầu quyền **Thiết bị ở gần** khi thiết bị hỗ trợ Bluetooth. Android 8–11 hiển thị trạng thái không cần cấp quyền riêng. Bước này kiểm tra quyền, có thể thực hiện trước khi ghép nối tai nghe. |
| Từ chối **Thông báo trạng thái** trên Android 13 trở lên | Quyền đọc thông báo và trạng thái cấp đủ quyền cốt lõi hoạt động độc lập với lựa chọn này. Trên Android 12 trở xuống, nếu đã tắt thông báo, nút mở trang cài đặt thông báo của ứng dụng. |
| Mở mục **Tối ưu hóa pin**, thay đổi lựa chọn rồi quay lại | Hàng pin phản ánh trạng thái Android báo cáo. Có thể hoàn tất các quyền cốt lõi với tối ưu hóa pin đang bật. |
| Tắt quyền truy cập thông báo trong Cài đặt rồi mở lại DriveVoice | Ứng dụng hiện **Chưa cấp** và trạng thái chung đổi thành **Chưa đủ quyền**. |

Nếu Android hiển thị **Chế độ cài đặt bị hạn chế** cho bản cài thử, mở thông tin ứng dụng DriveVoice và kiểm tra mục **Cho phép các chế độ cài đặt bị hạn chế** trong menu của hệ thống. [Trợ giúp Android](https://support.google.com/android/answer/12623953)

## Phạm vi bản build

- `app/build.gradle.kts` khai báo Compose Material 3, Hilt/KSP, Lifecycle, Coroutines và DataStore Preferences. Dữ liệu quyền được đọc trực tiếp từ Android.
- Phiên bắt đầu bằng thao tác của người dùng khi DriveVoice đang hiển thị. Foreground service dùng loại `mediaPlayback` và bổ sung loại `microphone` khi đã bật trả lời và cấp quyền. Micrô chỉ mở trong cửa sổ nhận giọng. [Loại foreground service của Android](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- Bộ lọc ưu tiên `MessagingStyle`. Nếu ứng dụng nguồn chỉ cung cấp tiêu đề và nội dung, DriveVoice dùng hai trường đó và loại các mẫu tổng hợp hoặc đang đồng bộ đã biết. Định dạng, nội dung bị ẩn và khả năng nhận diện người gửi còn tùy phiên bản của ứng dụng nguồn.
- Tin nhắn, dấu vết chống đọc lặp và hàng đợi chỉ tồn tại trong RAM. Mỗi tin được giới hạn 350 ký tự, mỗi nhóm tối đa 6 tin và khoảng 700 ký tự nội dung. Khi hàng đợi đầy, nhóm cũ nhất đang chờ bị loại để giới hạn bộ nhớ. Thông báo của DriveVoice chỉ hiện trạng thái phiên đọc.
- DriveVoice yêu cầu engine TTS của Google và chỉ gửi văn bản khi Android báo giọng tiếng Việt đã cài và hoạt động ngoại tuyến. Android quản lý đầu ra âm thanh. Manifest không yêu cầu quyền `INTERNET` hay quyền miễn tối ưu hóa pin trực tiếp. [API TextToSpeech](https://developer.android.com/reference/android/speech/tts/TextToSpeech)
- Nhận diện dùng `createOnDeviceSpeechRecognizer` với `vi-VN`. DriveVoice yêu cầu tuyến âm thanh giao tiếp Bluetooth và trả lại tuyến mặc định sau mỗi cửa sổ. Bản nháp, khóa thông báo và quyền gửi tạm thời chỉ tồn tại trong RAM. [SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer) · [AudioManager](https://developer.android.com/reference/android/media/AudioManager)
