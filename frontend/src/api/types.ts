export type Status = 'N' | 'W' | 'R' | 'C';
export type Tone = 'nys' | 'ontime' | 'early' | 'latestart' | 'delay' | 'res' | 'clo';
export type Role = 'BA' | 'BAL' | 'JM';
export type StepKey = 'create' | 'balr' | 'fixb' | 'jmr' | 'fixj';
export type LeaveTypeKey = 'full' | 'half' | 'fullTent' | 'halfTent';

export interface Member {
  name: string;
  full: string;
  role: Role;
  c: string;
  color: string;
  email: string;
  ext?: boolean;
  perm?: 'admin' | 'user';
}

export interface Worklog { date: string; who: string; h: number; note: string }

export interface Task {
  key: string;
  kind?: 'extra' | 'static' | 'other';
  title?: string;
  fid?: string;
  fname?: string;
  ba?: string;
  si?: number;
  sk?: StepKey;
  stepLabel?: string;
  isFix?: boolean;
  isRev?: boolean;
  phase: string;
  status: Status;
  logged: number;
  est: number;
  assignee: string;
  worker: string;
  ps: string;
  pe: string;
  rounds: number;
  held: number;
  prj: string;
  anken?: string;
  actS?: string | null;
  actE?: string | null;
  worklogs: Worklog[];
  // derived by the server
  isCur: boolean;
  order?: boolean;
  late: boolean;
  lateDays: number;
  soon: boolean;
  over: boolean;
  tone: Tone;
}

export interface Fn { id: string; name: string; ba: string; cur: number; cells: Task[] }

export interface Leave { id: number; who: string; date: string; type: LeaveTypeKey; note: string }

export interface LeaveType { label: string; ab: string; c: string; full?: boolean; half?: boolean; tent?: boolean }

export interface Config {
  hours: number; days: number[]; tsTol: number; soon: number; effWarn: number; held: number; rounds: number; cap: number;
}

export interface Anken {
  key: string; name: string; plan: string; members: string[]; matrix?: boolean;
  forecast?: string; late?: number; used?: number; est?: number; phases?: number[]; dates?: string[]; counts?: string[];
  baseUsed?: number; baseEst?: number;
}

export interface TsEntry { key: string; title: string; h: number; note: string }

export interface JiraSettings {
  url: string; email: string; tokenMasked: string; interval: string; status: 'ok' | 'fail' | null;
  epics: string[]; epicInfo: Record<string, { prj: string; title: string }>; lastFullSync: string;
}

export interface Settings {
  jira: JiraSettings;
  map: { ps: string; pe: string; eff: string };
  rule: string;
  alias: Record<StepKey, string>;
}

export interface Snapshot {
  today: string;
  weekEnd: string;
  lastSync: string | null;
  members: Record<string, Member>;
  config: Config;
  holidays: Record<string, [string, string]>;
  extraDays: Record<string, string>;
  leaves: Leave[];
  leaveTypes: Record<LeaveTypeKey | 'holiday', LeaveType>;
  projects: Record<string, { name: string; c: string }>;
  steps: { k: StepKey; label: string; dur: number; rev?: boolean; fix?: boolean }[];
  functions: Fn[];
  tasks: Task[];
  anken: Anken[];
  parents: { key: string; summary: string }[];
  jiraFields: { value: string; label: string; t: '' | 'date' | 'number' }[];
  timesheet: Record<string, Record<string, TsEntry[]>>;
  week: Record<string, number[]>;
  settings: Settings;
  nextFid: string;
}

export interface ImportRow {
  n: number; member: string; from: string; to: string; type: string; note: string;
  id: string | null; tk: LeaveTypeKey | null; err: string;
}
