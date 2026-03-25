# **Báo Cáo Nghiên Cứu Kiến Trúc Kỹ Thuật Và Chiến Lược Phát Triển Ứng Dụng Universal TV Remote Đa Nền Tảng**

## **Tổng Quan Về Dự Án Và Định Hướng Kiến Trúc**

Dự án phát triển ứng dụng điều khiển truyền hình (TV) từ xa mang tên "Best UED Remote TV of the Semester" đặt ra một bài toán kỹ thuật phức tạp, đòi hỏi sự hội tụ của nhiều lĩnh vực khoa học máy tính bao gồm lập trình socket mạng cục bộ, xử lý giao thức thiết bị đa dạng, truyền phát đa phương tiện thời gian thực và thiết kế trải nghiệm người dùng (UX) tối ưu. Qua việc phân tích Hình ảnh 1 và Hình ảnh 2, có thể nhận thấy một kho lưu trữ mã nguồn GitHub mang tên "UniRemote" trên nhánh "Dev-kietran", trong đó thành phần ngôn ngữ lập trình chủ đạo là Kotlin (65.0%) và HTML (35.0%). Cấu trúc mã nguồn này (với các tệp tin như build.gradle.kts, app, UI) cho thấy một nền tảng khởi đầu dựa trên lập trình thuần Android tự nhiên (Native Android). Tuy nhiên, chiến lược định hướng của dự án hiện tại ưu tiên mạnh mẽ cho việc phát triển theo hướng "Universal Remote" (Lựa chọn 2\) trên môi trường "đa nền tảng" nhằm tối đa hóa khả năng tiếp cận người dùng trên cả hệ sinh thái iOS và Android, đồng thời gia tăng điểm số ở tiêu chí "Độ khó kỹ thuật".

Việc chuyển dịch từ một ứng dụng thuần Kotlin sang một giải pháp đa nền tảng yêu cầu một sự đánh giá lại toàn diện về kiến trúc phần mềm. Ứng dụng giờ đây không chỉ đơn thuần là một giao diện gửi lệnh, mà phải đóng vai trò như một bộ định tuyến đa giao thức (multiprotocol router), có khả năng giao tiếp đồng thời với các hệ điều hành đóng kín như LG WebOS, Samsung Tizen, Android TV, Roku OS và Apple TV. Báo cáo nghiên cứu này sẽ cung cấp một bản thiết kế hệ thống chi tiết, đánh giá sâu sắc các khuôn khổ (framework) phát triển đa nền tảng, phân tích cơ chế hoạt động của các giao thức mạng cục bộ nhằm phục vụ việc kết nối tự động, khởi động hệ thống từ xa (Wake-on-LAN), phản chiếu màn hình độ trễ thấp, tích hợp đa nhiệm và lựa chọn kiến trúc máy chủ (backend) tối ưu. Đồng thời, báo cáo cũng sẽ đề xuất các chiến lược tiếp thị kỹ thuật số tuân thủ thuật toán của Facebook nhằm tối ưu hóa 40% điểm bình chọn của khán giả.

## **Đánh Giá Và Lựa Chọn Khuôn Khổ Phát Triển Đa Nền Tảng**

Trong bối cảnh phát triển ứng dụng di động đa nền tảng hiện đại, hai công nghệ thống trị thị trường là Flutter (phát triển bởi Google, sử dụng ngôn ngữ Dart) và React Native (phát triển bởi Meta, sử dụng JavaScript hoặc TypeScript). Quyết định lựa chọn giữa hai khuôn khổ này không chỉ ảnh hưởng đến tốc độ phát triển mà còn quyết định sự thành bại của các tính năng cốt lõi như phát hiện mạng cục bộ và phản hồi giao diện tức thì.

Khi đánh giá cấu trúc hiển thị đồ họa, ứng dụng điều khiển TV đòi hỏi sự phản hồi chính xác đến từng mili-giây. Các tính năng như bàn di chuột (Touchpad) đa điểm cần liên tục chuyển đổi các thao tác vuốt của người dùng thành các tín hiệu điều hướng mà không gặp phải tình trạng trễ khung hình. Flutter giải quyết vấn đề này thông qua việc loại bỏ hoàn toàn các cầu nối trung gian. Thay vì dựa vào các thành phần giao diện người dùng gốc của hệ điều hành, Flutter sử dụng công cụ kết xuất đồ họa riêng (Skia và hệ thống Impeller mới) để vẽ trực tiếp từng pixel lên màn hình thiết bị. Cách tiếp cận này đảm bảo rằng giao diện điều khiển, các phím macro, và các hiệu ứng phản hồi xúc giác hoạt động đồng nhất, mượt mà tuyệt đối trên mọi thiết bị. Ngược lại, React Native phụ thuộc vào một cầu nối JavaScript (JavaScript bridge) hoặc giao diện JSI để chuyển đổi mã JavaScript thành các thành phần gốc. Quá trình giao tiếp không đồng bộ này có thể tạo ra độ trễ siêu nhỏ (micro-stutters) khi xử lý các chuỗi sự kiện chạm phức tạp, làm giảm cảm giác "chân thực" của một chiếc điều khiển vật lý.

Xét về năng lực lập trình mạng cục bộ (Local Network Socket Programming), một yếu tố sống còn cho tính năng "Tự động kết nối thông minh", sự khác biệt giữa hai khuôn khổ càng trở nên rõ rệt. Tính năng này yêu cầu ứng dụng phải liên tục phát các gói tin UDP đa hướng (multicast UDP) để dò tìm thiết bị thông qua giao thức mDNS hoặc SSDP, đồng thời duy trì các kết nối TCP hoặc WebSocket bền bỉ với TV. Ngôn ngữ Dart của Flutter cung cấp các thư viện tiêu chuẩn nội tại cực kỳ mạnh mẽ để quản lý trực tiếp các kết nối RawDatagramSocket và WebSocket ở cấp độ hệ thống. Khả năng này cho phép các nhà phát triển thao tác với dữ liệu mạng ở mức độ byte, điều kiện bắt buộc để cấu trúc các gói tin điều khiển đặc thù hoặc gói tin Magic Packet để đánh thức thiết bị. Ở chiều ngược lại, React Native thiếu vắng sự hỗ trợ gốc cho các giao thức mạng mức thấp. Các nhà phát triển React Native thường phải phụ thuộc hoàn toàn vào các thư viện của bên thứ ba (như react-native-udp) vốn đòi hỏi việc liên kết phức tạp với mã nguồn gốc của Java hoặc Swift, dễ dẫn đến các lỗi tương thích khi cập nhật hệ điều hành và gây khó khăn cho việc bảo trì dài hạn.

| Tiêu chí Kỹ thuật | Phân tích giải pháp Flutter | Phân tích giải pháp React Native |
| :---- | :---- | :---- |
| **Kiến trúc Ngôn ngữ** | Dart (Kiểu tĩnh nghiêm ngặt, An toàn Null) giúp giảm thiểu lỗi thời gian chạy. | JavaScript / TypeScript mang tính linh hoạt cao nhưng dễ phát sinh lỗi chuyển đổi kiểu. |
| **Cơ chế Kết xuất (Rendering)** | Biên dịch trực tiếp (AOT) sang mã máy, vẽ giao diện thông qua Skia/Impeller, đảm bảo nhất quán tuyệt đối. | Sử dụng cầu nối (Bridge/JSI) giao tiếp với các thành phần giao diện Native, tiềm ẩn độ trễ. |
| **Quản lý Mạng Cục bộ (Socket)** | Tích hợp sẵn API mạnh mẽ cho UDP/TCP/WebSocket, hỗ trợ chạy ẩn ổn định. | Phụ thuộc sâu vào thư viện bên thứ ba (third-party modules) để mở cổng mạng. |
| **Mức độ Tương hợp Dự án TV** | Được đánh giá là lựa chọn dài hạn tối ưu cho các sản phẩm yêu cầu hiệu suất đồ họa cao và kết nối trực tiếp. | Phù hợp hơn cho các dự án tích hợp hệ sinh thái Web có sẵn, tốc độ khởi tạo MVP nhanh. |

Dựa trên những cơ sở phân tích trên, kiến trúc ứng dụng nên được triển khai bằng ngôn ngữ Dart trên khuôn khổ Flutter. Khả năng kiểm soát toàn diện luồng dữ liệu mạng kết hợp với kiến trúc đồ họa hiệu suất cao sẽ cung cấp một nền tảng vững chắc nhất để xử lý các luồng tín hiệu tương tác phức tạp với nhiều dòng TV khác nhau.

## **Chiến Lược Quản Lý Quyền Truy Cập Hệ Thống (Permissions & OS Security)**

Để ứng dụng có thể dò tìm thiết bị qua mạng nội bộ, truyền phát dữ liệu và duy trì trạng thái hoạt động ngay cả khi bị thu nhỏ (đa nhiệm), việc tuân thủ nghiêm ngặt các chính sách bảo mật mới nhất của iOS và Android là điều kiện bắt buộc:

* **Quét mạng cục bộ trên iOS (Từ iOS 14+):** Apple quản lý rất khắt khe quyền truy cập mạng LAN. Khi ứng dụng tương tác với các thiết bị thông qua Bonjour hoặc giao thức mạng cục bộ khác, bạn bắt buộc phải hỗ trợ quyền riêng tư mạng cục bộ trong iOS 14\. Ứng dụng phải khai báo khóa NSLocalNetworkUsageDescription và liệt kê các giao thức sử dụng trong mảng NSBonjourServices tại tệp Info.plist của dự án. Khi ứng dụng khởi tạo socket dò tìm mDNS/SSDP lần đầu tiên, iOS sẽ tự động bật hộp thoại yêu cầu người dùng cấp quyền kết nối nội bộ.  
* **Truy cập thiết bị lân cận trên Android (Từ Android 13+):** Đối với tính năng dò tìm TV qua Wi-Fi cục bộ, Android 13 (API 33\) đã giới thiệu quyền runtime mới là NEARBY\_WIFI\_DEVICES (thay thế cho quyền truy cập Vị Trí ACCESS\_FINE\_LOCATION vốn dễ gây hiểu lầm).1 Quyền này cần được khai báo trong tệp AndroidManifest.xml cùng với cờ neverForLocation. Nó phục vụ trực tiếp cho các tác vụ như phát hiện và kết nối với các thiết bị đa phương tiện (casting) hoặc thiết bị nhà thông minh qua mạng Wi-Fi.1  
* **Duy trì đa nhiệm chạy ngầm:** Để duy trì việc truyền phát hoặc phản chiếu màn hình (Screen Mirroring) khi ứng dụng rơi vào trạng thái nền, hệ điều hành Android 14+ yêu cầu ứng dụng phải khởi chạy Dịch vụ Tiền cảnh (Foreground Service) với kiểu mediaProjection. Tương tự trên iOS, cần phải triển khai Broadcast Upload Extension (thông qua ReplayKit) để duy trì luồng dữ liệu liên tục.  
* **Trải nghiệm người dùng (UX) khi xin quyền:** Không nên kích hoạt các hộp thoại xin quyền hệ thống ngay khi người dùng vừa mở ứng dụng. Thay vào đó, cần thiết kế một luồng hướng dẫn (Onboarding) giải thích minh bạch mục đích (ví dụ: *"Để kết nối với TV, ứng dụng cần truy cập mạng Wi-Fi nhà bạn"*). Khi người dùng nhấn đồng ý, ứng dụng mới kích hoạt lệnh gọi quyền hệ thống, qua đó tăng tối đa tỷ lệ chấp thuận.

## **Kiến Trúc Khám Phá Mạng Và Cơ Chế Tự Động Kết Nối (Seamless Auto-Reconnect)**

Tính năng "Tự động kết nối thông minh" đòi hỏi ứng dụng phải sở hữu khả năng tự động khôi phục phiên điều khiển ngay khi người dùng mở lại ứng dụng, loại bỏ hoàn toàn các thao tác cấu hình thủ công. Từ góc độ hệ thống, quá trình này là sự kết hợp của việc duy trì trạng thái dữ liệu cục bộ và việc thực thi các giao thức khám phá mạng đa hướng (Multicast Network Discovery Protocols).

Bản chất của môi trường mạng nội bộ là sự biến động. Một chiếc TV thông minh có thể được bộ định tuyến (router) cấp phát một địa chỉ IP hoàn toàn mới thông qua giao thức DHCP sau khi khởi động lại. Do đó, việc ứng dụng chỉ lưu trữ đơn thuần địa chỉ IP của TV là không đủ an toàn. Ứng dụng phải tiến hành lưu trữ một đối tượng phiên bản kết nối toàn diện vào cơ sở dữ liệu cục bộ an toàn trên thiết bị di động (ví dụ như thông qua các gói lưu trữ Hive hoặc sqflite của Flutter). Đối tượng này bao gồm địa chỉ IP cuối cùng, địa chỉ MAC vật lý của TV, định danh hệ điều hành (WebOS, Tizen, Android), và đặc biệt là các chứng chỉ bảo mật hoặc mã thông báo xác thực (auth tokens) đã được trao đổi trong lần ghép nối đầu tiên.

Sự đa dạng của các hệ điều hành TV yêu cầu ứng dụng phải triển khai song song hai giao thức phát hiện dịch vụ mạng khác nhau để tìm kiếm thiết bị:

Giao thức mDNS (Multicast DNS), còn được biết đến với tên gọi ZeroConf hoặc Bonjour, là tiêu chuẩn công nghiệp được sử dụng rộng rãi bởi các thiết bị Android TV và Apple TV. Khi ứng dụng di động kích hoạt tính năng dò tìm, nó sẽ gửi các truy vấn DNS thông qua gói tin UDP tới địa chỉ đa hướng 224.0.0.251 trên cổng 5353\. Quá trình này truy vấn các bản ghi con trỏ (PTR records) đặc thù, ví dụ như \_androidtvremote2.\_tcp.local.. Khi Android TV nhận được truy vấn này, nó sẽ phản hồi bằng các bản ghi SRV và TXT chứa địa chỉ IP hiện tại, số cổng dịch vụ và các siêu dữ liệu nhận dạng thiết bị.

Đối với các thiết bị như Roku, các dòng Smart TV của Samsung và nền tảng LG WebOS cũ, giao thức SSDP (Simple Service Discovery Protocol) đóng vai trò trung tâm. Thay vì sử dụng hệ thống tên miền, SSDP hoạt động dựa trên các thông điệp HTTP truyền qua giao thức UDP đa hướng tới địa chỉ 239.255.255.250 trên cổng 1900\. Ứng dụng sẽ gửi một yêu cầu M-SEARCH chứa chuỗi tiêu đề Search Target (ST) tương ứng với loại thiết bị cần tìm, chẳng hạn như roku:ecp cho Roku 2 hoặc udap:rootservice cho các dòng TV của LG.3 Thiết bị đích sẽ hồi đáp một thông báo chứa đường dẫn URL (Location URL) mô tả toàn bộ cấu hình dịch vụ mạng của TV.

Quy trình tự động kết nối lý tưởng được xây dựng theo một mô hình trạng thái (state machine) tối ưu độ trễ. Ngay khi ứng dụng khởi chạy, nó không phát tín hiệu đa hướng ngay lập tức mà ưu tiên thực hiện một kết nối trực tiếp (Direct Connection) tới địa chỉ IP đã lưu trong cơ sở dữ liệu. Thao tác này tiết kiệm đáng kể thời gian và tài nguyên mạng nếu cấu hình mạng không thay đổi. Trong trường hợp kết nối trực tiếp thất bại do thời gian chờ (timeout) hoặc bị từ chối, hệ thống sẽ tự động kích hoạt vòng lặp thử lại dựa trên thuật toán lùi thời gian theo cấp số nhân có tích hợp độ nhiễu ngẫu nhiên (exponential backoff with jitter). Thuật toán này giúp ứng dụng chờ đợi 1 giây, sau đó 2 giây, rồi 4 giây giữa các lần kết nối, ngăn chặn tình trạng làm quá tải bộ điều hợp mạng của TV. Đồng thời, trong lúc chờ đợi, ứng dụng sẽ âm thầm phát đi các luồng mDNS và SSDP đa hướng. Nếu TV đã thay đổi IP, phản hồi từ các giao thức này sẽ cung cấp địa chỉ IP mới gắn với địa chỉ MAC cũ, cho phép ứng dụng tự động cập nhật cơ sở dữ liệu và tái lập kết nối hoàn toàn vô hình đối với người dùng cuối.

## **Cấu Trúc Lớp Đa Trừu Tượng Cho Giao Thức Điều Khiển (Lấy Cảm Hứng Từ Connect SDK)**

Giá trị cốt lõi của tiêu chí "Độ khó kỹ thuật" nằm ở việc trừu tượng hóa các phương thức giao tiếp mạng riêng biệt của từng hãng truyền hình thành một hệ thống điều khiển đồng nhất. Thay vì phân tán các lệnh cấu hình rải rác, hệ thống nên học hỏi kiến trúc từ các dự án mã nguồn mở uy tín như **Connect SDK** (một framework mã nguồn mở tích hợp và trừu tượng hóa việc khám phá và kết nối giữa nhiều nền tảng TV).

Ứng dụng sẽ được thiết kế với mẫu **Factory Method** kết hợp **Strategy Pattern**. Lớp giao diện (UI Layer) chỉ phát ra một lệnh chung duy nhất (ví dụ: Command.VOLUME\_UP). Lớp phần mềm trung gian (Middleware) sẽ tiếp nhận ý định này, kiểm tra loại TV đang kết nối và phân phối xuống các tệp triển khai (Implementation Class) đặc thù của từng hệ điều hành:

### **1\. Giao Thức Remote Protocol V2 (Android TV)**

Đây là giao thức kỹ thuật phức tạp nhất, được sử dụng bởi Google TV/Android TV. Khác với văn bản thuần, giao thức này yêu cầu giao tiếp thông qua Protocol Buffers (Protobuf) và mã hóa hoàn toàn qua kênh TLS 1.2.4 Các nhà phát triển có thể tham khảo logic dịch ngược từ dự án nguồn mở androidtvremote2 của *tronikos*.4 Quá trình yêu cầu khởi tạo kết nối đến cổng 6466/6467, trao đổi chứng chỉ mã hóa tự ký, và nhập mã PIN 6 ký tự để ghép nối (pairing).5 Khi gửi lệnh phím, các mã lệnh như KEYCODE\_DPAD\_CENTER (23) hay KEYCODE\_BACK (4) sẽ được mã hóa bằng protobuf và truyền đi.4

### **2\. Giao Thức MRP \- Media Remote Protocol (Apple TV)**

Các thế hệ Apple TV từ tvOS 4 trở lên sử dụng giao thức độc quyền MRP. Tương tự Android TV, MRP dùng Protobuf truyền qua TCP nhưng bảo mật khắt khe hơn bằng mã hóa SRP (Secure Remote Password). Việc tự triển khai hệ thống mã hóa SRP nguyên bản trên mobile là cực kỳ khó khăn. Chiến lược tối ưu là nghiên cứu các dự án như pyatv hoặc node-appletv, sau đó đẩy quy trình xác thực SRP phức tạp này lên Backend Node.js xử lý như một trạm trung chuyển (Bridge), giúp Mobile App giảm tải.

### **3\. Giao Thức SSAP (LG WebOS)**

Các TV của LG giao tiếp qua giao thức Simple Service Access Protocol (SSAP) trên nền tảng WebSockets (cổng 3000 cho kết nối không mã hóa, hoặc 3001 cho WSS bảo mật).6 Dữ liệu trao đổi là JSON. Ví dụ, lệnh bật ứng dụng hoặc chỉnh âm lượng sẽ được gửi tới các endpoint ảo như ssap://audio/volumeUp.8 Để làm "chuột bay" (Magic Remote), ứng dụng gọi endpoint getPointerInputSocket để TV trả về một WebSocket mới chuyên dụng, từ đó liên tục truyền tọa độ X/Y.8

### **4\. Giao Thức ECP (Roku OS)**

Thiết bị Roku sử dụng External Control Protocol (ECP), một RESTful API qua cổng 8060\.2 Không cần duy trì kết nối liên tục, ứng dụng chỉ cần gửi yêu cầu HTTP POST đơn giản (vd: http://\<IP\>:8060/keypress/Home).2 Lưu ý, từ phiên bản Roku OS 14.1, tính năng "Control by mobile apps" trong phần cài đặt mạng của TV bắt buộc phải được kích hoạt thì lệnh ECP mới có tác dụng.2

### **5\. Giao Thức SmartView SDK (Samsung Tizen)**

Nền tảng Tizen sử dụng WebSockets trên cổng 8001 (hoặc 8002 WSS).9 Cấu trúc lệnh JSON đòi hỏi thuộc tính "method": "ms.remote.control" và "TypeOfRemote": "SendRemoteKey".9

## **Chiến Lược Xử Lý Đa Nhiệm (Multitasking) Và Vòng Đời Ứng Dụng**

Một "điểm đau" (pain point) cực lớn của các ứng dụng remote là mất kết nối khi người dùng thu nhỏ ứng dụng để trả lời tin nhắn. Các hệ điều hành hiện đại (như iOS và Android) sẽ lập tức đóng băng (suspend) hoặc cắt đứt các socket TCP/WebSocket chạy ngầm để tiết kiệm pin. Chiến lược xử lý như sau:

1. **Quản lý vòng đời (App Lifecycle) chặt chẽ:** Đừng ép hệ điều hành duy trì socket vô ích ở chế độ nền. Khi ứng dụng bị đẩy xuống Background, hãy chủ động đóng kết nối một cách an toàn (graceful disconnect). Khi ứng dụng được mở lại (Foreground/Resumed), lập tức sử dụng IP và token đã lưu để thực thi auto-reconnect trong bóng tối.  
2. **Đa nhiệm truyền phát (Media Casting):** Thay vì dùng Screen Mirroring bắt buộc điện thoại phải luôn mở màn hình, hãy sử dụng **Google Cast SDK** (hoặc chuẩn **DLNA** mã nguồn mở) cho tính năng phát video/ảnh. Kiến trúc này biến điện thoại thành một trạm định tuyến (Sender): nó gửi URL của video cho TV (Receiver). TV sẽ tự tải và tự phát. Ngay sau khi lệnh Cast thành công, điện thoại có thể tắt màn hình hoặc chuyển sang app khác mà video trên TV vẫn tiếp tục phát liền mạch. Hệ thống Google Cast còn hỗ trợ "Trình điều khiển thu nhỏ" (Mini Controller) hiển thị trên thanh thông báo, giúp người dùng dễ dàng dừng/phát ngay cả khi đang ở ngoài ứng dụng.

## **Công Nghệ Đánh Thức Thiết Bị Thông Minh (Smart Auto-Wake)**

Khi một chiếc Smart TV tiến vào trạng thái ngủ sâu (Sleep/Standby mode), mạch mạng của nó sẽ ngừng phản hồi TCP hoặc WebSocket. Phương thức duy nhất để khởi động thiết bị là giao thức Wake-on-LAN (WoL) hoặc Wake on Wireless LAN (WoWLAN).10

Ứng dụng cần cấu trúc một "Gói tin ma thuật" (Magic Packet). Chuỗi nhị phân này dài 102 byte: bắt đầu bằng 6 byte 0xFF, theo sau là địa chỉ MAC vật lý của TV đích được lặp lại chính xác 16 lần.12 Ứng dụng Flutter sẽ khởi tạo UDP socket và phát sóng đa hướng (broadcast) gói tin này tới địa chỉ subnet (thường là 255.255.255.255) trên cổng 7 hoặc cổng 9\.12

Rào cản lớn nhất là cài đặt Fast Boot hoặc Eco Mode của TV thường tắt hẳn bộ thu Wi-Fi.13 Do đó, UX của ứng dụng cần cung cấp các tooltip hướng dẫn người dùng vào phần cài đặt nâng cao của TV (ví dụ: kích hoạt "Wake on Magic Packet" hoặc "Network Standby") để tính năng này hoạt động trơn tru.14

## **Cơ Chế Khởi Động Nhanh Ứng Dụng (Quick App Launch)**

Tính năng "Khởi động nhanh ứng dụng" yêu cầu ứng dụng di động lấy danh sách app đang có trên TV.

* **WebOS:** Gửi lệnh WebSocket đến ssap://com.webos.applicationManager/listLaunchPoints để lấy danh sách App ID (vd: youtube.leanback.v4) và biểu tượng (icon).15 Sau đó, dùng endpoint ssap://system.launcher/launch kèm App ID để mở app.6  
* **Android TV:** Do lý do bảo mật, Android TV không cho phép liệt kê package bên thứ ba. Nhà phát triển nên tạo sẵn một cơ sở dữ liệu các App ID phổ biến. Khi người dùng bấm, ứng dụng dùng giao thức Deep Link truyền các siêu liên kết URI (như viki://home hoặc https://app.primevideo.com) qua kênh V2 để buộc Android TV mở ứng dụng đích.17  
* **Roku:** Hỗ trợ tính năng DIAL (Discovery and Launch) hoặc ECP để lấy icon (query/icon) và khởi chạy ứng dụng trực tiếp bằng REST API.2

## **Tương Tác Đa Phương Tiện: Phản Chiếu Màn Hình (Screen Mirroring)**

Khác với Media Casting (đã đề cập ở phần Đa nhiệm), Screen Mirroring yêu cầu chụp màn hình điện thoại theo thời gian thực (30-60 fps) và truyền đến TV với độ trễ siêu thấp (\< 500ms).

Để đạt được "độ trễ bằng 0" (ultra-low latency), công nghệ **WebRTC** là giải pháp tối ưu nhất, vượt trội hơn các giao thức HTTP truyền thống nhờ việc sử dụng UDP và RTP để bơm gói tin liên tục, bỏ qua cơ chế chờ đệm (buffering).18

Triển khai trên Flutter yêu cầu 3 bước:

1. **Chụp màn hình (Screen Capture):** Sử dụng gói flutter\_webrtc gọi getDisplayMedia(). Trên Android 14+, bắt buộc phải chạy dịch vụ Foreground Service loại mediaProjection *trước khi* yêu cầu quyền chụp màn hình, nếu không ứng dụng sẽ bị crash ngay lập tức (SecurityException).19 Trên iOS, cần sử dụng tính năng Broadcast Upload Extension (ReplayKit).20  
2. **Signaling (Báo hiệu mạng):** Hai thiết bị cần trao đổi SDP (Session Description Protocol) và các ứng viên ICE để tìm đường kết nối P2P. Quá trình này bắt buộc phải có một Backend Server.  
3. **Bộ giải mã:** Tivi cần được thiết lập một điểm nhận (receiver) tương thích WebRTC để giải mã luồng video.

## **Tối Ưu Hóa Kiến Trúc Backend: Xây Dựng Custom Server Với NestJS**

Thay vì phụ thuộc vào Firebase (vốn yếu thế trong xử lý kết nối song công liên tục cho WebRTC), hệ thống nên triển khai kiến trúc backend chuyên biệt bằng **NestJS** kết hợp TypeScript. NestJS cung cấp cấu trúc module hóa mạnh mẽ (Clean Architecture) sử dụng Dependency Injection (Tiêm phụ thuộc), giúp tách biệt rạch ròi giữa Controller và Service. Điều này đặc biệt hữu ích cho các dự án mở rộng, hiệu suất cao.

Kiến trúc backend này đóng vai trò sống còn trong việc:

* **WebRTC Signaling:** Quản lý hàng vạn luồng WebSockets (thông qua Socket.io) với độ trễ cực thấp để trao đổi tín hiệu SDP/ICE giữa mobile và TV phục vụ Screen Mirroring.  
* **Apple TV Bridge:** Dùng Node.js chạy một lớp proxy để mã hóa/giải mã các thông điệp SRP Protobuf của Apple TV, giúp thiết bị di động không phải xử lý thuật toán mã hóa quá nặng.  
* **Đồng bộ Macro:** Lưu trữ cấu hình phím Macro (chuỗi lệnh tĩnh) của người dùng vào PostgreSQL thông qua các JSON Schema được xác thực nghiêm ngặt.21

**Cấu trúc thư mục Backend chuyên nghiệp (Feature-based Module):**

uniremote-backend/

├── src/

│ ├── config/ \# Cấu hình toàn cục (env, database, socket)

│ ├── api/ \# Phân chia định tuyến theo phiên bản API (v1, v2)

│ │ └── v1/

│ │ ├── auth/ \# Module quản lý xác thực người dùng (JWT)

│ │ └── macros/ \# Module lưu trữ cấu hình chuỗi lệnh tự động

│ ├── services/ \# Tầng Business Logic nghiệp vụ cốt lõi

│ ├── models/ \# Định nghĩa cấu trúc Schema (PostgreSQL/TypeORM)

│ ├── sockets/ \# Tầng quản lý WebSockets thời gian thực

│ │ ├── signaling.ts \# Xử lý trao đổi tín hiệu WebRTC (SDP, ICE candidates)

│ │ └── connection.ts \# Quản lý vòng đời kết nối của client

│ ├── middleware/ \# Guards xác thực token, Filters xử lý lỗi tập trung

│ ├── remote\_protocol/ \# LỚP TRỪU TƯỢNG GIAO THỨC (Factory Pattern)

│ │ ├── protocol.factory.ts \# Nhận lệnh chung và phân tuyến dựa trên OS của TV

│ │ ├── tizen.client.ts \# Triển khai WebSocket cổng 8001 cho Samsung

│ │ ├── webos.client.ts \# Triển khai SSAP cổng 3000 cho LG

│ │ └── apple\_tv.bridge.ts \# Proxy mã hóa SRP/Protobuf cho Apple TV

│ └── main.ts \# Entry point khởi chạy ứng dụng NestJS

├──.env \# Chứa các biến môi trường cấu hình (ẩn khỏi Git)

├── package.json

└── tsconfig.json

## **Thiết Kế Giao Diện (UI/UX) Đa Chức Năng**

Thiết kế giao diện chia thành 4 không gian tương tác (4-tab architecture) lấy giao diện tối (Dark Mode) làm trọng tâm, tích hợp phản hồi xúc giác (Haptic Feedback) để bù đắp sự thiếu hụt của phím bấm vật lý:

1. **D-Pad & Core Controls:** Trung tâm điều khiển đa chiều, âm lượng, chuyển kênh.  
2. **Touchpad & Keyboard:** Bàn di chuột vuốt mượt mà điều khiển con trỏ TV, kết hợp bàn phím điện thoại để nhập liệu tìm kiếm nhanh.22  
3. **App Launcher:** Lưới đồ họa chứa biểu tượng các ứng dụng (trích xuất từ API khởi động nhanh), loại bỏ bước tìm kiếm rườm rà.  
4. **Lập trình Macro:** Cho phép ghép nối chuỗi lệnh tự động. (VD: Bật TV → chờ 2 giây → Chỉnh âm lượng → Mở Netflix).

## **Chiến Lược Xúc Tiến Truyền Thông Video Trên Facebook**

Để đạt điểm tối đa ở phần bình chọn Khán giả, video Promo phải tối ưu hóa theo thuật toán Facebook:

1. **Không dùng "Engagement Bait":** Tránh hoàn toàn việc kêu gọi "Hãy Like và Share" lộ liễu để không bị AI của Facebook giáng cấp bài đăng.23  
2. **Định dạng và Thời lượng:** Thời lượng 30-90 giây, có phụ đề lớn, nhịp điệu nhanh.24  
3. **Cấu trúc Video:**  
   * *0-7s (Hook):* Nỗi đau mất remote, hết pin.  
   * *7-20s (Solution):* Ứng dụng tự động tìm thấy TV ngay lập tức.  
   * *20-45s (Features):* Phô diễn tính năng gõ phím nhanh, Wake-on-LAN bật TV khi màn hình đang tắt, Screen Mirroring mượt mà.  
   * *45-60s (CTA):* Khuyến khích tag bạn bè thay vì share (VD: "Tag ngay người chuyên làm mất remote vào đây").25

## **Kết Luận**

Giải pháp phát triển Universal TV Remote dựa trên nền tảng Flutter chứng minh được sự ưu việt không thể thay thế. Việc cấu trúc lại hệ thống kết nối thông qua *Lớp Trừu Tượng Giao Thức (Protocol Abstraction Layer)* — học hỏi từ Connect SDK — kết hợp cơ chế kiểm soát vòng đời nền khắt khe giúp ứng dụng "giao tiếp" mượt mà đa nhiệm với WebOS, Tizen, Android TV và Roku. Đồng thời, máy chủ backend tùy chỉnh NestJS giải quyết triệt để vấn đề báo hiệu WebRTC cho Screen Mirroring và đóng vai trò trạm chuyển tiếp (bridge) cho các giao thức bảo mật đóng như Apple TV. Khi các tính năng phức tạp này được gói gọn trong thiết kế UI tối giản và chiến dịch marketing thông minh, dự án sở hữu tiềm năng thương mại hóa thực tế rất mạnh mẽ.

#### **Nguồn trích dẫn**

1. Behavior changes: Apps targeting Android 13 or higher, truy cập vào tháng 3 25, 2026, [https://developer.android.com/about/versions/13/behavior-changes-13](https://developer.android.com/about/versions/13/behavior-changes-13)  
2. External Control Protocol (ECP) \- Roku Developer, truy cập vào tháng 3 25, 2026, [https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md](https://developer.roku.com/docs/developer-program/dev-tools/external-control-api.md)  
3. UDAP Specifications (For Second Screen TV and Companion Apps), truy cập vào tháng 3 25, 2026, [https://webostv.developer.lge.com/assets/netcast/NetCast-UDAP.pdf](https://webostv.developer.lge.com/assets/netcast/NetCast-UDAP.pdf)  
4. tronikos/androidtvremote2: A Python library implementing the Android TV Remote protocol v2 \- GitHub, truy cập vào tháng 3 25, 2026, [https://github.com/tronikos/androidtvremote2](https://github.com/tronikos/androidtvremote2)  
5. AndroidTVRemoteControl on CocoaPods.org, truy cập vào tháng 3 25, 2026, [https://cocoapods.org/pods/AndroidTVRemoteControl](https://cocoapods.org/pods/AndroidTVRemoteControl)  
6. webos package \- github.com/kaperys/go-webos \- Go Packages, truy cập vào tháng 3 25, 2026, [https://pkg.go.dev/github.com/kaperys/go-webos](https://pkg.go.dev/github.com/kaperys/go-webos)  
7. Socket connection to LG TV \- Web App Development \- webOS TV Community, truy cập vào tháng 3 25, 2026, [https://forum.webostv.developer.lge.com/t/socket-connection-to-lg-tv/3360](https://forum.webostv.developer.lge.com/t/socket-connection-to-lg-tv/3360)  
8. Sending remote press via the command line \- webOS TV Developer Forum, truy cập vào tháng 3 25, 2026, [https://forum.webostv.developer.lge.com/t/sending-remote-press-via-the-command-line/19147](https://forum.webostv.developer.lge.com/t/sending-remote-press-via-the-command-line/19147)  
9. Samsung TV via websockets API : r/crestron \- Reddit, truy cập vào tháng 3 25, 2026, [https://www.reddit.com/r/crestron/comments/vx9ak4/samsung\_tv\_via\_websockets\_api/](https://www.reddit.com/r/crestron/comments/vx9ak4/samsung_tv_via_websockets_api/)  
10. herzhenr/simple-wake-on-lan \- GitHub, truy cập vào tháng 3 25, 2026, [https://github.com/herzhenr/simple-wake-on-lan](https://github.com/herzhenr/simple-wake-on-lan)  
11. Download | Samsung Developer, truy cập vào tháng 3 25, 2026, [https://developer.samsung.com/smarttv/develop/extension-libraries/smart-view-sdk/download.html](https://developer.samsung.com/smarttv/develop/extension-libraries/smart-view-sdk/download.html)  
12. Building a Wake On Lan Packet \- j2i.net, truy cập vào tháng 3 25, 2026, [https://blog.j2i.net/2024/11/06/building-a-wake-on-lan-packet/](https://blog.j2i.net/2024/11/06/building-a-wake-on-lan-packet/)  
13. Made a free app to turn on your PC from your iPhone using Wake-on-LAN — no more getting out of bed \- Reddit, truy cập vào tháng 3 25, 2026, [https://www.reddit.com/r/buildapc/comments/1qyans8/made\_a\_free\_app\_to\_turn\_on\_your\_pc\_from\_your/](https://www.reddit.com/r/buildapc/comments/1qyans8/made_a_free_app_to_turn_on_your_pc_from_your/)  
14. Guide to Setting Up and Testing Wake-on-Lan (WoL) \- Granite River Labs, truy cập vào tháng 3 25, 2026, [https://www.graniteriverlabs.com/en-us/technical-blog/wol-wake-on-lan](https://www.graniteriverlabs.com/en-us/technical-blog/wol-wake-on-lan)  
15. Get all App IDs of all installed Apps on LG webOS TV \- Stack Overflow, truy cập vào tháng 3 25, 2026, [https://stackoverflow.com/questions/55898625/get-all-app-ids-of-all-installed-apps-on-lg-webos-tv](https://stackoverflow.com/questions/55898625/get-all-app-ids-of-all-installed-apps-on-lg-webos-tv)  
16. Get icon of installed apps on WebOS TV LG \- Stack Overflow, truy cập vào tháng 3 25, 2026, [https://stackoverflow.com/questions/62712131/get-icon-of-installed-apps-on-webos-tv-lg](https://stackoverflow.com/questions/62712131/get-icon-of-installed-apps-on-webos-tv-lg)  
17. Android TV Remote \- App Links/Deep Linking \- Guide \- Home Assistant Community, truy cập vào tháng 3 25, 2026, [https://community.home-assistant.io/t/android-tv-remote-app-links-deep-linking-guide/567921](https://community.home-assistant.io/t/android-tv-remote-app-links-deep-linking-guide/567921)  
18. Low-Latency WebRTC Streaming: Real-Time Video at Scale \- Flussonic, truy cập vào tháng 3 25, 2026, [https://flussonic.com/blog/article/low-latency-webrtc-streaming](https://flussonic.com/blog/article/low-latency-webrtc-streaming)  
19. Flutter WebRTC Screen Sharing on Android 14+: The Missing Guide | by Jerome Jumah, truy cập vào tháng 3 25, 2026, [https://medium.com/@owinojumahjerome/flutter-webrtc-screen-sharing-on-android-14-the-missing-guide-4f45391055f3](https://medium.com/@owinojumahjerome/flutter-webrtc-screen-sharing-on-android-14-the-missing-guide-4f45391055f3)  
20. Screen sharing \- LiveKit Documentation, truy cập vào tháng 3 25, 2026, [https://docs.livekit.io/transport/media/screenshare/](https://docs.livekit.io/transport/media/screenshare/)  
21. JSON Schema: The Secret to Building Scalable and Maintainable Data Models, truy cập vào tháng 3 25, 2026, [https://romanglushach.medium.com/json-schema-the-secret-to-building-scalable-and-maintainable-data-models-2c456d90f73b](https://romanglushach.medium.com/json-schema-the-secret-to-building-scalable-and-maintainable-data-models-2c456d90f73b)  
22. Universal TV Remote Control | Smart App for Samsung, LG & Roku \- MWM, truy cập vào tháng 3 25, 2026, [https://mwm.ai/apps/id/6759975770](https://mwm.ai/apps/id/6759975770)  
23. Like & Share Competitions Are Killing Your Facebook Marketing \- EMBARK, truy cập vào tháng 3 25, 2026, [https://embark.studio/journal/are-like-share-competitions-allowed-on-facebook/](https://embark.studio/journal/are-like-share-competitions-allowed-on-facebook/)  
24. How Do I Create a Promo Video for My Mobile App?, truy cập vào tháng 3 25, 2026, [https://thisisglance.com/learning-centre/how-do-i-create-a-promo-video-for-my-mobile-app](https://thisisglance.com/learning-centre/how-do-i-create-a-promo-video-for-my-mobile-app)  
25. How to Run Contests and Giveaways That Actually Go Viral | Viral Loops Insider, truy cập vào tháng 3 25, 2026, [https://viral-loops.com/blog/how-to-run-contests-and-giveaways/](https://viral-loops.com/blog/how-to-run-contests-and-giveaways/)