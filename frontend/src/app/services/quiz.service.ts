import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { QuizStartResponse, QuizAnswerResponse, QuizResultsResponse } from '../models/quiz.model';

@Injectable({ providedIn: 'root' })
export class QuizService {
  constructor(private http: HttpClient) {}

  startQuiz(category?: string, difficulty?: string): Observable<QuizStartResponse> {
    return this.http.post<QuizStartResponse>('/api/quiz/start', { category, difficulty });
  }

  submitAnswer(answerIndex: number): Observable<QuizAnswerResponse> {
    return this.http.post<QuizAnswerResponse>('/api/quiz/answer', { answerIndex });
  }

  getResults(): Observable<QuizResultsResponse> {
    return this.http.get<QuizResultsResponse>('/api/quiz/results');
  }

  endQuiz(): Observable<any> {
    return this.http.post('/api/quiz/end', {});
  }

  getCategories(): Observable<{ categories: string[] }> {
    return this.http.get<{ categories: string[] }>('/api/quiz/categories');
  }
}
