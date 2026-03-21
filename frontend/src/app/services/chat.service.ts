import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ChatResponse, FeedbackRequest, StatsResponse, LearningStatsResponse } from '../models/chat.model';

@Injectable({ providedIn: 'root' })
export class ChatService {
  constructor(private http: HttpClient) {}

  sendMessage(message: string): Observable<ChatResponse> {
    return this.http.post<ChatResponse>('/api/chat', { message });
  }

  sendMessageStream(message: string): Observable<string> {
    return new Observable(subscriber => {
      fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message })
      }).then(response => {
        if (!response.ok) {
          subscriber.error(new Error('Stream request failed'));
          return;
        }
        const reader = response.body?.getReader();
        const decoder = new TextDecoder();
        if (!reader) { subscriber.complete(); return; }

        let buffer = '';

        const read = (): void => {
          reader.read().then(({ done, value }) => {
            if (done) { subscriber.complete(); return; }

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
              const trimmed = line.trim();
              if (!trimmed || trimmed === ':') continue;

              if (trimmed.startsWith('data:')) {
                const data = trimmed.slice(5).trim();
                if (data === '[DONE]') continue;
                try {
                  const parsed = JSON.parse(data);
                  if (parsed.content) {
                    subscriber.next(parsed.content);
                  }
                } catch {}
              }
            }
            read();
          }).catch(err => subscriber.error(err));
        };
        read();
      }).catch(err => subscriber.error(err));
    });
  }

  sendFeedback(feedback: 'helpful' | 'unhelpful'): Observable<any> {
    return this.http.post('/api/feedback', { feedback } as FeedbackRequest);
  }

  getStats(): Observable<StatsResponse> {
    return this.http.get<StatsResponse>('/api/stats');
  }

  getLearningStats(): Observable<LearningStatsResponse> {
    return this.http.get<LearningStatsResponse>('/api/learning-stats');
  }

  getCategories(): Observable<{ categories: string[] }> {
    return this.http.get<{ categories: string[] }>('/api/categories');
  }
}
