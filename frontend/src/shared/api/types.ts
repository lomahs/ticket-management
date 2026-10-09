import type { components } from './schema';

export type Schemas = components['schemas'];

export type Meta = Schemas['Meta'];
export type Member = Schemas['Member'];
export type TaskCard = Schemas['TaskCard'];
export type Status = 'N' | 'W' | 'R' | 'C';
export type Tone = 'nys' | 'ontime' | 'early' | 'latestart' | 'delay' | 'res' | 'clo';
export type StepKind = 'create' | 'balr' | 'fixb' | 'jmr' | 'fixj';
export type LeaveTypeKey = 'full' | 'half' | 'fullTent' | 'halfTent';
