import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ChatService } from '../../services/chat.service';
import { StatsResponse, LearningStatsResponse } from '../../models/chat.model';

@Component({
  selector: 'app-analytics',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './analytics.component.html',
  styleUrl: './analytics.component.scss'
})
export class AnalyticsComponent implements OnInit {
  loading = true;
  hasData = false;
  stats: StatsResponse = { messageCount: 0, duration: '0 min', sentimentBreakdown: { positive: 0, neutral: 0, negative: 0 } };
  learning: LearningStatsResponse = { totalFeedback: 0, helpfulPercentage: 0 };

  constructor(private chatService: ChatService) {}

  ngOnInit() {
    this.loadStats();
  }

  loadStats() {
    this.loading = true;
    Promise.all([
      this.chatService.getStats().toPromise().catch(() => null),
      this.chatService.getLearningStats().toPromise().catch(() => null)
    ]).then(([s, l]) => {
      if (s) this.stats = s;
      if (l) this.learning = l;
      this.hasData = this.stats.messageCount > 0 || this.learning.totalFeedback > 0;
      this.loading = false;
    });
  }

  get sentimentTotal(): number {
    const sb = this.stats.sentimentBreakdown;
    return (sb.positive || 0) + (sb.neutral || 0) + (sb.negative || 0);
  }

  sentimentPercent(type: 'positive' | 'neutral' | 'negative'): number {
    const total = this.sentimentTotal;
    return total > 0 ? Math.round(((this.stats.sentimentBreakdown[type] || 0) / total) * 100) : 0;
  }

  get interactions(): number {
    return this.stats.messageCount + this.learning.totalFeedback;
  }
}
