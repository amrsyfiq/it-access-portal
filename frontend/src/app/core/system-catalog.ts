/**
 * Systems shown on the "Request access" page. This is a fixed list of choices for the UI,
 * not data pretending to come from the API: the backend accepts any system name and stores
 * whatever is submitted. (Option for later: a SYSTEM_CATALOG table + GET /api/systems.)
 */
export interface CatalogSystem {
  name: string;
  category: 'APPLICATION' | 'PLATFORM' | 'DATA';
  description: string;
  /** Short monogram shown in the tile (no vendor logos). */
  monogram: string;
  /** Tile colour, as a CSS colour. */
  color: string;
}

export const SYSTEM_CATALOG: readonly CatalogSystem[] = [
  {
    name: 'GitLab',
    category: 'APPLICATION',
    description: 'Source code, merge requests and CI/CD pipelines.',
    monogram: 'GL',
    color: '#e24329',
  },
  {
    name: 'Jira',
    category: 'APPLICATION',
    description: 'Issue tracking and sprint planning.',
    monogram: 'JI',
    color: '#2684ff',
  },
  {
    name: 'Confluence',
    category: 'APPLICATION',
    description: 'Team documentation and knowledge base.',
    monogram: 'CF',
    color: '#1868db',
  },
  {
    name: 'HR System',
    category: 'APPLICATION',
    description: 'Employee records, leave and onboarding.',
    monogram: 'HR',
    color: '#7c3aed',
  },
  {
    name: 'Payroll',
    category: 'APPLICATION',
    description: 'Manage payroll runs and employee pay information.',
    monogram: 'PY',
    color: '#be123c',
  },
  {
    name: 'AWS Console',
    category: 'PLATFORM',
    description: 'Cloud computing and storage services.',
    monogram: 'AW',
    color: '#d97706',
  },
  {
    name: 'Datadog',
    category: 'PLATFORM',
    description: 'Cloud monitoring, dashboards and alerting.',
    monogram: 'DD',
    color: '#632ca6',
  },
  {
    name: 'Snowflake',
    category: 'DATA',
    description: 'Data warehousing and analytics.',
    monogram: 'SF',
    color: '#0ea5e9',
  },
  {
    name: 'SAP ERP',
    category: 'APPLICATION',
    description: 'Enterprise resource planning and finance.',
    monogram: 'SP',
    color: '#0369a1',
  },
];
