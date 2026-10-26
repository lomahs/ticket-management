import type { CSSProperties } from 'react';
import {
  AlarmClock, ArrowDown, ArrowLeft, ArrowRight, ArrowUp, ArrowUpDown, Calendar, CalendarDays, Camera, ChartGantt, Check,
  ChevronDown, ChevronLeft, ChevronRight, ChevronUp, CircleAlert, CircleCheck, CirclePause, CloudUpload, Download,
  ExternalLink, Eye, EyeOff, FileCheck, FileDown, FileSpreadsheet, FileUp, Grid3x3, GripVertical, Hourglass, LayoutDashboard, Link, ListTodo, LogOut,
  MessageSquare, Moon, PanelLeftClose, PanelLeftOpen, Plug, Plus, RefreshCw, Repeat, Scale, ScanSearch, Search, Send,
  Settings, Sun, Sunrise, Timer, Trash, TrendingUp, Users, TriangleAlert, Undo2, Upload, X, type LucideIcon,
} from 'lucide-react';

/** Lucide icons used by the app, keyed by their lucide-static names (as in the design). */
const ICONS = {
  'alarm-clock': AlarmClock, 'arrow-down': ArrowDown, 'arrow-left': ArrowLeft, 'arrow-right': ArrowRight,
  'arrow-up': ArrowUp, 'arrow-up-down': ArrowUpDown, calendar: Calendar, 'calendar-days': CalendarDays,
  camera: Camera, 'chart-gantt': ChartGantt, check: Check, 'chevron-down': ChevronDown, 'chevron-left': ChevronLeft,
  'chevron-right': ChevronRight, 'chevron-up': ChevronUp, 'circle-alert': CircleAlert, 'circle-check': CircleCheck,
  'pause-circle': CirclePause, 'upload-cloud': CloudUpload, download: Download, 'external-link': ExternalLink, eye: Eye, 'eye-off': EyeOff,
  'file-check': FileCheck, 'file-down': FileDown, 'file-spreadsheet': FileSpreadsheet, 'file-up': FileUp,
  'grid-3x3': Grid3x3, 'grip-vertical': GripVertical, hourglass: Hourglass, 'layout-dashboard': LayoutDashboard, link: Link, 'list-todo': ListTodo, 'log-out': LogOut,
  'message-square': MessageSquare, moon: Moon, 'panel-left-close': PanelLeftClose, 'panel-left-open': PanelLeftOpen,
  plug: Plug, plus: Plus, 'refresh-cw': RefreshCw, repeat: Repeat, scale: Scale, 'scan-search': ScanSearch, search: Search, send: Send,
  settings: Settings, sun: Sun, sunrise: Sunrise, timer: Timer, 'trash-2': Trash, 'trending-up': TrendingUp,
  'triangle-alert': TriangleAlert, 'undo-2': Undo2, upload: Upload, users: Users, x: X,
} satisfies Record<string, LucideIcon>;

export type IconName = keyof typeof ICONS;

export interface IconProps {
  name: IconName;
  size?: number;
  color?: string;
  label?: string;
  style?: CSSProperties;
}

export function Icon({ name, size = 16, color = 'currentColor', label, style }: IconProps) {
  const Cmp = ICONS[name];
  return (
    <Cmp
      size={size}
      color={color}
      role={label ? 'img' : undefined}
      aria-label={label}
      aria-hidden={label ? undefined : true}
      style={{ display: 'inline-block', flex: 'none', ...style }}
    />
  );
}
