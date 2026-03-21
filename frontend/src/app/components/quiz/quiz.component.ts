import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { QuizService } from '../../services/quiz.service';
import { QuizQuestionDto, QuizResultsResponse, BreakdownItem } from '../../models/quiz.model';

@Component({
  selector: 'app-quiz',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './quiz.component.html',
  styleUrl: './quiz.component.scss'
})
export class QuizComponent implements OnInit {
  screen: 'start' | 'question' | 'results' = 'start';
  categories: string[] = [];
  selectedCategory = '';
  selectedDifficulty = '';
  currentQuestion: QuizQuestionDto | null = null;
  questionNumber = 1;
  totalQuestions = 10;
  feedbackMessage = '';
  feedbackCorrect = false;
  showFeedback = false;
  results: QuizResultsResponse | null = null;
  letters = ['A', 'B', 'C', 'D'];
  optionsDisabled = false;
  selectedOption = -1;

  constructor(private quizService: QuizService) {}

  ngOnInit() {
    this.quizService.getCategories().subscribe({
      next: data => this.categories = data.categories,
      error: () => {}
    });
  }

  startQuiz() {
    this.quizService.startQuiz(
      this.selectedCategory || undefined,
      this.selectedDifficulty || undefined
    ).subscribe({
      next: data => {
        this.totalQuestions = data.totalQuestions;
        this.questionNumber = 1;
        this.currentQuestion = data.currentQuestion;
        this.screen = 'question';
        this.resetFeedback();
      },
      error: () => alert('Failed to start quiz.')
    });
  }

  submitAnswer(index: number) {
    if (this.optionsDisabled) return;
    this.optionsDisabled = true;
    this.selectedOption = index;

    this.quizService.submitAnswer(index).subscribe({
      next: data => {
        this.feedbackCorrect = data.correct;
        this.feedbackMessage = data.explanation;
        this.showFeedback = true;

        if (data.complete) {
          setTimeout(() => this.loadResults(), 100);
        } else {
          this.currentQuestion = data.nextQuestion;
        }
      }
    });
  }

  nextQuestion() {
    this.questionNumber++;
    this.resetFeedback();
  }

  private resetFeedback() {
    this.showFeedback = false;
    this.feedbackMessage = '';
    this.optionsDisabled = false;
    this.selectedOption = -1;
  }

  private loadResults() {
    this.quizService.getResults().subscribe({
      next: data => {
        this.results = data;
        this.screen = 'results';
      }
    });
  }

  restart() {
    this.screen = 'start';
    this.results = null;
    this.resetFeedback();
  }

  get progressPercent(): number {
    return (this.questionNumber / this.totalQuestions) * 100;
  }

  formatCategory(cat: string): string {
    return cat.replace(/_/g, ' ').replace(/\b\w/g, l => l.toUpperCase());
  }
}
