import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ChatService } from '../../services/chat.service';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit {
  messageCount = 0;
  feedbackCount = 0;
  helpfulRate = 0;

  features = [
    { title: 'AI Chat Assistant', desc: 'Ask questions about cybersecurity topics and get expert answers powered by AI', route: '/chat', icon: 'chat', color: 'cyan', tags: ['Real-time', 'AI-powered'] },
    { title: 'Security Quiz', desc: 'Test your cybersecurity knowledge with interactive quizzes across multiple difficulty levels', route: '/quiz', icon: 'quiz', color: 'purple', tags: ['15+ Questions', 'Multiple Levels'] },
    { title: 'Security Playbooks', desc: 'Step-by-step guides for handling security incidents and implementing best practices', route: '/playbooks', icon: 'book', color: 'green', tags: ['8+ Guides', 'Step-by-step'] },
    { title: 'Analytics Dashboard', desc: 'Track your learning progress, view statistics, and analyze your cybersecurity knowledge growth', route: '/analytics', icon: 'chart', color: 'orange', tags: ['Progress Tracking', 'Insights'] }
  ];

  constructor(private chatService: ChatService) {}

  ngOnInit() {
    this.chatService.getStats().subscribe({
      next: stats => this.messageCount = stats.messageCount,
      error: () => {}
    });
    this.chatService.getLearningStats().subscribe({
      next: stats => {
        this.feedbackCount = stats.totalFeedback;
        this.helpfulRate = Math.round(stats.helpfulPercentage);
      },
      error: () => {}
    });
  }
}
