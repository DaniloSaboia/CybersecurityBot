import { Component, ElementRef, ViewChild, ChangeDetectorRef, AfterViewChecked } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChatService } from '../../services/chat.service';
import { ChatMessage } from '../../models/chat.model';

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat.component.html',
  styleUrl: './chat.component.scss'
})
export class ChatComponent implements AfterViewChecked {
  @ViewChild('messagesContainer') private messagesContainer!: ElementRef;

  messages: ChatMessage[] = [
    { role: 'bot', content: 'Welcome to CyberGuard AI! I\'m your cybersecurity expert. Ask me about passwords, phishing, malware, encryption, network security, or real-world incidents.', timestamp: new Date() }
  ];

  inputText = '';
  isLoading = false;
  streamingContent = '';
  private shouldScroll = false;

  constructor(private chatService: ChatService, private cdr: ChangeDetectorRef) {}

  ngAfterViewChecked() {
    if (this.shouldScroll) {
      this.scrollToBottom();
      this.shouldScroll = false;
    }
  }

  sendMessage() {
    const text = this.inputText.trim();
    if (!text || this.isLoading) return;

    this.messages.push({ role: 'user', content: text, timestamp: new Date() });
    this.inputText = '';
    this.isLoading = true;
    this.streamingContent = '';
    this.shouldScroll = true;
    this.cdr.detectChanges();

    this.chatService.sendMessageStream(text).subscribe({
      next: chunk => {
        this.streamingContent += chunk;
        this.shouldScroll = true;
        this.cdr.detectChanges();
      },
      error: () => {
        this.messages.push({ role: 'bot', content: 'Sorry, I encountered an error. Please make sure Ollama is running.', timestamp: new Date() });
        this.isLoading = false;
        this.streamingContent = '';
        this.cdr.detectChanges();
      },
      complete: () => {
        if (this.streamingContent) {
          this.messages.push({ role: 'bot', content: this.streamingContent, timestamp: new Date() });
        }
        this.streamingContent = '';
        this.isLoading = false;
        this.shouldScroll = true;
        this.cdr.detectChanges();
      }
    });
  }

  sendFeedback(feedback: 'helpful' | 'unhelpful', idx: number) {
    this.chatService.sendFeedback(feedback).subscribe();
    this.messages[idx].sentiment = feedback;
    this.cdr.detectChanges();
  }

  onKeydown(event: KeyboardEvent) {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      this.sendMessage();
    }
  }

  formatContent(content: string): string {
    return content
      .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
      .replace(/\*(.*?)\*/g, '<em>$1</em>')
      .replace(/`(.*?)`/g, '<code>$1</code>')
      .replace(/\n/g, '<br>');
  }

  private scrollToBottom() {
    try {
      this.messagesContainer.nativeElement.scrollTop = this.messagesContainer.nativeElement.scrollHeight;
    } catch {}
  }
}
