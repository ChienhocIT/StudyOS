"use client";

import { useState } from "react";
import { ArrowRight, Check, RotateCcw } from "lucide-react";

const cards = [
  {
    question: "Active recall là gì?",
    answer:
      "Chủ động nhớ lại kiến thức mà không nhìn tài liệu, chẳng hạn tự trả lời một câu hỏi.",
  },
  {
    question: "Vì sao nên ôn tập ngắt quãng?",
    answer:
      "Ôn lại ở những thời điểm cách nhau giúp củng cố trí nhớ thay vì dồn toàn bộ vào một buổi.",
  },
  {
    question: "Trích dẫn giúp ích gì khi học với AI?",
    answer:
      "Trích dẫn giúp bạn kiểm tra câu trả lời và đọc lại ngữ cảnh trong tài liệu gốc.",
  },
];

export function FlashcardDemo() {
  const [index, setIndex] = useState(0);
  const [revealed, setRevealed] = useState(false);
  const done = index === cards.length;
  return (
    <div className="lp-demo">
      <div className="lp-demo-top">
        <span>Flashcard mẫu</span>
        <span>
          {Math.min(index + 1, cards.length)} / {cards.length}
        </span>
      </div>
      <div className="lp-demo-body" aria-live="polite" aria-atomic="true">
        {done ? (
          <>
            <Check className="lp-demo-check" size={32} />
            <h3>Thêm một điều đã nhớ.</h3>
            <p>Bạn đã xem hết bộ thẻ mẫu. Hãy thử với tài liệu của mình.</p>
          </>
        ) : (
          <>
            <span className="lp-demo-label">
              {revealed ? "Gợi ý trả lời" : "Thử nhớ trước khi lật thẻ"}
            </span>
            <h3>{cards[index].question}</h3>
            {revealed && <p className="lp-answer">{cards[index].answer}</p>}
          </>
        )}
      </div>
      <button
        className="lp-button lp-demo-action"
        onClick={() => {
          if (done) {
            setIndex(0);
            setRevealed(false);
          } else if (revealed) {
            setIndex(index + 1);
            setRevealed(false);
          } else setRevealed(true);
        }}
      >
        {done ? "Thử lại" : revealed ? "Thẻ tiếp theo" : "Lật thẻ"}
        {done ? <RotateCcw size={17} /> : <ArrowRight size={17} />}
      </button>
      <p className="lp-demo-note">Bản minh họa, không lưu tiến độ.</p>
    </div>
  );
}
