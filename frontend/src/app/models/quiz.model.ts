export interface QuizStartResponse {
  totalQuestions: number;
  currentQuestion: QuizQuestionDto | null;
  questionNumber: number;
}

export interface QuizQuestionDto {
  question: string;
  options: string[];
  category: string;
  difficulty: string;
}

export interface QuizAnswerResponse {
  correct: boolean;
  explanation: string;
  complete: boolean;
  nextQuestion: QuizQuestionDto | null;
}

export interface QuizResultsResponse {
  score: number;
  total: number;
  percentage: number;
  timeTaken: string;
  breakdown: BreakdownItem[];
}

export interface BreakdownItem {
  question: string;
  yourAnswer: string;
  correctAnswer: string;
  correct: boolean;
  explanation: string;
}
