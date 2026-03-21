import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Playbook } from '../models/playbook.model';
import { firstValueFrom } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class PlaybookService {
  private playbooks: Playbook[] = [];
  private loaded = false;

  constructor(private http: HttpClient) {}

  async ensureLoaded(): Promise<void> {
    if (this.loaded) return;
    this.playbooks = await firstValueFrom(
      this.http.get<Playbook[]>('/assets/data/playbooks.json')
    );
    this.loaded = true;
  }

  getPlaybooks(): Playbook[] {
    return this.playbooks;
  }

  getPlaybookById(id: string): Playbook | undefined {
    return this.playbooks.find(p => p.id === id);
  }

  getCategories(): string[] {
    return [...new Set(this.playbooks.map(p => p.category))].sort();
  }

  getPlaybooksByCategory(category: string): Playbook[] {
    return this.playbooks.filter(p => p.category === category);
  }

  searchPlaybooks(query: string, category?: string): Playbook[] {
    let result = this.playbooks;
    if (category && category !== 'all') {
      result = result.filter(p => p.category === category);
    }
    if (query) {
      const q = query.toLowerCase();
      result = result.filter(p =>
        p.title.toLowerCase().includes(q) ||
        p.description.toLowerCase().includes(q) ||
        p.category.toLowerCase().includes(q)
      );
    }
    return result;
  }
}
