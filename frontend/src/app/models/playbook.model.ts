export interface Playbook {
  id: string;
  title: string;
  description: string;
  category: string;
  icon: string;
  severity: 'critical' | 'high' | 'medium' | 'low';
  steps: PlaybookStep[];
}

export interface PlaybookStep {
  title: string;
  content: string;
  warning?: string;
  tip?: string;
}
