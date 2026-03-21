import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { PlaybookService } from '../../services/playbook.service';
import { Playbook } from '../../models/playbook.model';

@Component({
  selector: 'app-playbooks',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './playbooks.component.html',
  styleUrl: './playbooks.component.scss'
})
export class PlaybooksComponent implements OnInit {
  playbooks: Playbook[] = [];
  categories: string[] = [];
  selectedCategory = 'all';
  searchQuery = '';
  selectedPlaybook: Playbook | null = null;
  expandedSteps: Set<number> = new Set();

  constructor(private playbookService: PlaybookService) {}

  async ngOnInit() {
    await this.playbookService.ensureLoaded();
    this.playbooks = this.playbookService.getPlaybooks();
    this.categories = this.playbookService.getCategories();
  }

  get filteredPlaybooks(): Playbook[] {
    return this.playbookService.searchPlaybooks(this.searchQuery, this.selectedCategory);
  }

  selectCategory(cat: string) {
    this.selectedCategory = cat;
  }

  openPlaybook(p: Playbook) {
    this.selectedPlaybook = p;
    this.expandedSteps = new Set([0]);
  }

  closePlaybook() {
    this.selectedPlaybook = null;
    this.expandedSteps.clear();
  }

  toggleStep(i: number) {
    if (this.expandedSteps.has(i)) {
      this.expandedSteps.delete(i);
    } else {
      this.expandedSteps.add(i);
    }
  }

  isStepExpanded(i: number): boolean {
    return this.expandedSteps.has(i);
  }

  getCategoryCount(cat: string): number {
    return this.playbookService.getPlaybooksByCategory(cat).length;
  }

  getSeverityClass(severity: string): string {
    return severity;
  }
}
