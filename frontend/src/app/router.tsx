import type { QueryClient } from '@tanstack/react-query';
import { createRootRouteWithContext, createRoute, createRouter, lazyRouteComponent, Outlet, redirect, type RouterHistory } from '@tanstack/react-router';
import { z } from 'zod';
// Search schemas and the tab list are imported directly so each screen's code can load lazily.
import { LoginPage, NoAccessPage } from '../features/auth';
import { calendarSearch, newLeaveSearch } from '../features/calendar/search';
import { dailySearch } from '../features/daily/search';
import { ticketCheckSearch } from '../features/ticket-check/search';
import { burndownSearch, timesheetSearch } from '../features/effort/search';
import { SCHEDULE_FILTERS } from '../features/overview/search';
import { matrixSearch } from '../features/matrix/search';
import { myWorkSearch } from '../features/my-work/search';
import { UsersPage } from '../features/users';
import { planningSearch } from '../features/planning/search';
import { isSettingsTab, settingsSearch } from '../features/settings/search';
import { ApiError } from '../shared/api/errors';
import { can, meQuery, visibleAnkenQuery } from '../shared/api/queries';
import type { Feature } from '../shared/api/types';
import { homeOf } from '../shared/nav';
import { storedAnken } from '../shared/state/anken';
import { AppShell, NotFound } from './AppShell';

const calendarPages = () => import('../features/calendar');
const effortPages = () => import('../features/effort');
const matrixPages = () => import('../features/matrix');

interface Ctx { queryClient: QueryClient }

/** On every screen: `ticket` is open in the side panel; `anken` is the global switcher's anken (useAnkenScope). */
const root = createRootRouteWithContext<Ctx>()({
  validateSearch: z.object({ ticket: z.string().optional().catch(undefined), anken: z.string().optional().catch(undefined) }),
  component: Outlet,
  notFoundComponent: NotFound,
});

/** The signed-in user, or a redirect to /login (no session). */
async function loadMe(queryClient: QueryClient, next: string) {
  try {
    return await queryClient.ensureQueryData(meQuery);
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) throw redirect({ to: '/login', search: { next } });
    throw e;
  }
}

/** Everything inside the sidebar shell needs an active user. */
const app = createRoute({
  getParentRoute: () => root,
  id: 'app',
  component: AppShell,
  notFoundComponent: NotFound,
  beforeLoad: async ({ context, location }) => {
    const me = await loadMe(context.queryClient, location.href);
    if (me.access !== 'active') throw redirect({ to: '/no-access' });
  },
});

const login = createRoute({
  getParentRoute: () => root,
  path: 'login',
  validateSearch: z.object({ next: z.string().optional().catch(undefined) }),
  component: LoginPage,
});
const noAccess = createRoute({
  getParentRoute: () => root,
  path: 'no-access',
  beforeLoad: async ({ context, location }) => { await loadMe(context.queryClient, location.href); },
  component: NoAccessPage,
});

/** The first screen the user may read, in sidebar order; /no-access when there is none. */
async function home(queryClient: QueryClient) {
  return homeOf(await queryClient.ensureQueryData(meQuery)) ?? '/no-access';
}

/** `staticData.feature` of a screen: without read on it the shell shows "no access" instead of the screen (AppShell). */
const needs = (feature: Feature) => ({ staticData: { feature } });

const index = createRoute({ getParentRoute: () => app, path: '/', beforeLoad: async ({ context }) => { throw redirect({ to: await home(context.queryClient) }); } });
/**
 * A search middleware that drops `keys` when a navigation changes the switcher's anken: they belong to one anken (a phase,
 * a person of its scope) and would show another anken's data or a 404.
 */
function dropOnAnkenChange<K extends string>(...keys: K[]) {
  return <S extends { anken?: string } & Partial<Record<K, unknown>>>({ search, next }: { search: S; next: (s: S) => S }): S => {
    const out = next(search);
    return out.anken === search.anken ? out : { ...out, ...Object.fromEntries(keys.map((k) => [k, undefined])) };
  };
}

// A member picked in another anken may not be a person of the new one; without it My Work shows the viewer's own work.
const myWork = createRoute({
  getParentRoute: () => app,
  path: 'my-work',
  ...needs('my_work'),
  validateSearch: myWorkSearch,
  search: { middlewares: [dropOnAnkenChange('member')] },
  component: lazyRouteComponent(() => import('../features/my-work'), 'MyWorkPage'),
});

/**
 * "Function Matrix" in the sidebar: the switcher's anken; under "All my anken" the first visible anken that has functions,
 * else the first one (its matrix explains why it is empty).
 */
const matrixEntry = createRoute({
  getParentRoute: () => app,
  path: 'matrix',
  ...needs('matrix'),
  beforeLoad: async ({ context, search }) => {
    if (!can(await context.queryClient.ensureQueryData(meQuery), 'matrix')) return;
    const anken = await context.queryClient.ensureQueryData(visibleAnkenQuery);
    const scope = search.anken || storedAnken();
    const first = anken.find((a) => a.key === scope) ?? anken.find((a) => a.has_functions) ?? anken[0];
    throw first ? redirect({ to: '/anken/$epic/matrix', params: { epic: first.key } }) : redirect({ to: '/anken' });
  },
});
/** "Tickets" in the sidebar: the switcher's anken, else the first visible one. */
const ticketsEntry = createRoute({
  getParentRoute: () => app,
  path: 'tickets',
  ...needs('ticket'),
  beforeLoad: async ({ context, search }) => {
    if (!can(await context.queryClient.ensureQueryData(meQuery), 'ticket')) return;
    const anken = await context.queryClient.ensureQueryData(visibleAnkenQuery);
    const scope = (search as { anken?: string }).anken || storedAnken();
    const first = anken.find((a) => a.key === scope) ?? anken[0];
    throw first ? redirect({ to: '/tickets/$epic', params: { epic: first.key } }) : redirect({ to: '/anken' });
  },
});
const tickets = createRoute({ getParentRoute: () => app, path: 'tickets/$epic', ...needs('ticket'), component: lazyRouteComponent(() => import('../features/tickets'), 'TicketsPage') });
const overview = createRoute({ getParentRoute: () => app, path: 'anken', ...needs('overview'), component: lazyRouteComponent(() => import('../features/overview'), 'OverviewPage') });
const ankenDetail = createRoute({ getParentRoute: () => app, path: 'anken/$epic', ...needs('overview'), component: lazyRouteComponent(() => import('../features/overview'), 'OverviewPage') });
const ticketList = createRoute({
  getParentRoute: () => app,
  path: 'anken/$epic/tickets',
  ...needs('overview'),
  validateSearch: z.object({ label: z.enum(SCHEDULE_FILTERS).catch('overdue'), phase: z.string().optional().catch(undefined) }),
  component: lazyRouteComponent(() => import('../features/overview'), 'TicketListPage'),
});
const matrix = createRoute({ getParentRoute: () => app, path: 'anken/$epic/matrix', ...needs('matrix'), validateSearch: matrixSearch, component: lazyRouteComponent(matrixPages, 'MatrixPage') });
const addFunction = createRoute({ getParentRoute: () => app, path: 'anken/$epic/matrix/new', ...needs('matrix'), component: lazyRouteComponent(matrixPages, 'AddFunctionPage') });
const phaseSettings = createRoute({ getParentRoute: () => app, path: 'anken/$epic/matrix/phases', ...needs('matrix'), component: lazyRouteComponent(matrixPages, 'PhaseSettingsPage') });

const daily = createRoute({
  getParentRoute: () => app,
  path: 'daily',
  ...needs('overview'),
  validateSearch: dailySearch,
  search: { middlewares: [dropOnAnkenChange('date', 'phase')] },
  component: lazyRouteComponent(() => import('../features/daily'), 'DailyPage'),
});

const ticketCheck = createRoute({
  getParentRoute: () => app,
  path: 'ticket-check',
  ...needs('overview'),
  validateSearch: ticketCheckSearch,
  component: lazyRouteComponent(() => import('../features/ticket-check'), 'TicketCheckPage'),
});

const planning = createRoute({ getParentRoute: () => app, path: 'planning', ...needs('planning'), validateSearch: planningSearch, component: lazyRouteComponent(() => import('../features/planning'), 'PlanningPage') });
const planningSchedule = createRoute({ getParentRoute: () => app, path: 'planning/schedule', ...needs('planning'), component: lazyRouteComponent(() => import('../features/planning'), 'MasterSchedulePage') });
const planningEstimation = createRoute({ getParentRoute: () => app, path: 'planning/estimation', ...needs('planning'), component: lazyRouteComponent(() => import('../features/planning'), 'EstimationPage') });

const effort = createRoute({ getParentRoute: () => app, path: 'effort', ...needs('effort'), beforeLoad: () => { throw redirect({ to: '/effort/timesheet' }); } });
const timesheet = createRoute({ getParentRoute: () => app, path: 'effort/timesheet', ...needs('effort'), validateSearch: timesheetSearch, component: lazyRouteComponent(effortPages, 'TimesheetPage') });
const analysis = createRoute({ getParentRoute: () => app, path: 'effort/analysis', ...needs('effort'), component: lazyRouteComponent(effortPages, 'AnalysisPage') });
const burndown = createRoute({
  getParentRoute: () => app,
  path: 'effort/burndown',
  ...needs('effort'),
  validateSearch: burndownSearch,
  search: { middlewares: [dropOnAnkenChange('phase', 'task', 'member')] },
  component: lazyRouteComponent(effortPages, 'BurndownPage'),
});

const calendar = createRoute({ getParentRoute: () => app, path: 'calendar', ...needs('calendar'), validateSearch: calendarSearch, component: lazyRouteComponent(calendarPages, 'CalendarPage') });
const newLeave = createRoute({ getParentRoute: () => calendar, path: 'leave/new', validateSearch: newLeaveSearch, component: lazyRouteComponent(calendarPages, 'LeaveDialogRoute') });
const editLeave = createRoute({ getParentRoute: () => calendar, path: 'leave/$id', component: lazyRouteComponent(calendarPages, 'LeaveDialogRoute') });
const importLeave = createRoute({ getParentRoute: () => calendar, path: 'import/$step', component: lazyRouteComponent(calendarPages, 'ImportWizardRoute') });

/** Users and Settings are for admins; anyone else goes to their first screen. */
async function adminOnly(queryClient: QueryClient) {
  const me = await queryClient.ensureQueryData(meQuery);
  if (!me.admin) throw redirect({ to: homeOf(me) ?? '/no-access' });
}

const settings = createRoute({
  getParentRoute: () => app,
  path: 'settings',
  beforeLoad: async ({ context }) => {
    await adminOnly(context.queryClient);
    throw redirect({ to: '/settings/$tab', params: { tab: 'jira' } });
  },
});
const settingsTab = createRoute({
  getParentRoute: () => app,
  path: 'settings/$tab',
  validateSearch: settingsSearch,
  beforeLoad: async ({ context, params }) => {
    await adminOnly(context.queryClient);
    if (!isSettingsTab(params.tab)) throw redirect({ to: '/settings/$tab', params: { tab: 'jira' } });
  },
  component: lazyRouteComponent(() => import('../features/settings'), 'SettingsPage'),
});

const adminUsers = createRoute({
  getParentRoute: () => app,
  path: 'admin/users',
  beforeLoad: ({ context }) => adminOnly(context.queryClient),
  component: UsersPage,
});

const routeTree = root.addChildren([
  login, noAccess,
  app.addChildren([
  index, myWork, matrixEntry, ticketsEntry, tickets, overview, ankenDetail, ticketList, matrix, addFunction, phaseSettings, daily, ticketCheck, planning, planningEstimation, planningSchedule, effort, timesheet, analysis, burndown,
  calendar.addChildren([newLeave, editLeave, importLeave]), settings, settingsTab, adminUsers,
  ]),
]);

export function makeRouter(queryClient: QueryClient, history?: RouterHistory) {
  return createRouter({ routeTree, context: { queryClient }, history, defaultPreload: 'intent', scrollRestoration: true });
}

declare module '@tanstack/react-router' {
  interface Register { router: ReturnType<typeof makeRouter> }
  interface StaticDataRouteOption { feature?: Feature }
}
