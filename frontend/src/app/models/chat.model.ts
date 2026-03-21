export interface ChatMessage {
  role: 'user' | 'bot';
  content: string;
  timestamp: Date;
  sentiment?: string;
}

export interface ChatRequest {
  message: string;
}

export interface ChatResponse {
  response: string;
}

export interface FeedbackRequest {
  feedback: 'helpful' | 'unhelpful';
}

export interface StatsResponse {
  messageCount: number;
  duration: string;
  sentimentBreakdown: { positive: number; neutral: number; negative: number };
}

export interface LearningStatsResponse {
  totalFeedback: number;
  helpfulPercentage: number;
}
