import {
  BookOpen,
  Brain,
  Layers3,
  MessageSquareText,
  Route,
  Clapperboard,
} from "lucide-react";

export const features = [
  {
    icon: BookOpen,
    title: "Tài liệu & ghi chú",
    description: "Tập hợp nguồn học trong từng sổ tay.",
    href: "#tai-lieu",
  },
  {
    icon: MessageSquareText,
    title: "Hỏi đáp có trích dẫn",
    description: "Hiểu câu trả lời từ chính tài liệu của bạn.",
    href: "#hoi-dap",
  },
  {
    icon: Layers3,
    title: "Quiz & flashcard",
    description: "Chủ động nhớ lại, thay vì chỉ đọc lại.",
    href: "#flashcard",
  },
  {
    icon: Route,
    title: "Lộ trình & ôn tập",
    description: "Biết hôm nay nên dành thời gian cho điều gì.",
    href: "#lo-trinh",
  },
  {
    icon: Brain,
    title: "Tiến độ kiến thức",
    description: "Nhìn lại mức độ nắm vững từng chủ đề.",
    href: "#lo-trinh",
  },
  {
    icon: Clapperboard,
    title: "Language Lab",
    description: "Luyện ngôn ngữ từ nội dung video.",
    href: "#language-lab",
  },
] as const;

export const faqs = [
  {
    question: "StudyOS giúp tôi học như thế nào?",
    answer:
      "StudyOS kết nối tài liệu, ghi chú, hỏi đáp có trích dẫn, quiz và flashcard trong một không gian. Bạn có thể kiểm tra kiến thức rồi tiếp tục ôn tập theo tiến độ của mình.",
  },
  {
    question: "Tôi có thể bắt đầu với những nguồn nào?",
    answer:
      "Bạn có thể tải tài liệu lên hoặc thêm nguồn qua liên kết trong sổ tay. Trạng thái xử lý hiển thị ngay trong ứng dụng; hãy chờ nguồn sẵn sàng trước khi hỏi đáp hoặc tạo nội dung ôn tập.",
  },
  {
    question: "Câu trả lời AI có dẫn nguồn không?",
    answer:
      "Phần hỏi đáp sử dụng nội dung trong nguồn học để tìm bằng chứng và hiển thị trích dẫn khi có. Bạn nên mở trích dẫn để kiểm tra ngữ cảnh. Chất lượng câu trả lời phụ thuộc vào tài liệu và cấu hình model của hệ thống.",
  },
  {
    question: "Flashcard trên trang này có lưu tiến độ không?",
    answer:
      "Widget trên landing page là ví dụ tương tác để bạn thử cách học. Để tạo flashcard từ tài liệu và lưu tiến độ ôn tập, hãy chọn Bắt đầu học, đăng nhập và mở sổ tay của bạn.",
  },
  {
    question: "Tôi cần làm gì để bắt đầu?",
    answer:
      "Chọn Bắt đầu học để đến ứng dụng. Tạo tài khoản hoặc đăng nhập, tạo không gian học và sổ tay, rồi thêm tài liệu đầu tiên.",
  },
] as const;
