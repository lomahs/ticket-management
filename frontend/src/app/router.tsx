import type { QueryClient } from '@tanstack/react-query';
import { createRootRouteWithContext, createRoute, createRouter, lazyRouteComponent, Outlet, redirect, type RouterHistory } from '@tanstack/react-router';
import { z } from 'zod';
// Search schemas, the overview query and the tab list are imported directly so each screen's code can load lazily.
import { LoginPage, NoAccessPage } from '../features/auth';
import { calendarSearch, newLeaveSearch } from '../features/calendar/search';
import { analysisSearch, burndownSearch, timesheetSearch } from '../features/effort/search';
import { matrixSearch } from '../features/matrix/search';
import { myWorkSearch } from '../features/my-work/search';
import { overviewQuery } from '../features/overview/api';
import { planningSearch } from '../features/planning/search';
import { isSettingsTab } from '../features/settings/search';
import { ApiError } from '../shared/api/errors';
import { meQuery } from '../shared/api/queries';
import { AppShell, NotFound } from './AppShell';

const calendarPages = () => import('../features/calendar');
const effortPages = () => import('../features/effort');
const matrixPages = () => import('../features/matrix');

interface Ctx { queryClient: QueryClient }

const root = createRootRouteWithContext<Ctx>()({
  validateSearch: z.object({ ticket: z.string().optional().catch(undefined) }),
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

const index = createRoute({ getParentRoute: () => app, path: '/', beforeLoad: () => { throw redirect({ to: '/my-work' }); } });
const myWork = createRoute({ getParentRoute: () => app, path: 'my-work', validateSearch: myWorkSearch, component: lazyRouteComponent(() => import('../features/my-work'), 'MyWorkPage') });

/** "Function Matrix" in the sidebar: the first tracked anken that has functions, else the first one (its matrix explains why it is empty). */
const matrixEntry = createRoute({
  getParentRoute: () => app,
  path: 'matrix',
  beforeLoad: async ({ context }) => {
    const anken = await context.queryClient.ensureQueryData(overviewQuery);
    const first = anken.find((a) => a.has_functions) ?? anken[0];
    throw first ? redirect({ to: '/anken/$epic/matrix', params: { epic: first.key } }) : redirect({ to: '/anken' });
  },
});
const overview = createRoute({ getParentRoute: () => app, path: 'anken', component: lazyRouteComponent(() => import('../features/overview'), 'OverviewPage') });
const ankenDetail = createRoute({ getParentRoute: () => app, path: 'anken/$epic', component: lazyRouteComponent(() => import('../features/overview'), 'OverviewPage') });
const matrix = createRoute({ getParentRoute: () => app, path: 'anken/$epic/matrix', validateSearch: matrixSearch, component: lazyRouteComponent(matrixPages, 'MatrixPage') });
const addFunction = createRoute({ getParentRoute: () => app, path: 'anken/$epic/matrix/new', component: lazyRouteComponent(matrixPages, 'AddFunctionPage') });
const phaseSettings = createRoute({ getParentRoute: () => app, path: 'anken/$epic/matrix/phases', component: lazyRouteComponent(matrixPages, 'PhaseSettingsPage') });

const planning = createRoute({ getParentRoute: () => app, path: 'planning', validateSearch: planningSearch, component: lazyRouteComponent(() => import('../features/planning'), 'PlanningPage') });

const effort = createRoute({ getParentRoute: () => app, path: 'effort', beforeLoad: () => { throw redirect({ to: '/effort/timesheet' }); } });
const timesheet = createRoute({ getParentRoute: () => app, path: 'effort/timesheet', validateSearch: timesheetSearch, component: lazyRouteComponent(effortPages, 'TimesheetPage') });
const analysis = createRoute({ getParentRoute: () => app, path: 'effort/analysis', validateSearch: analysisSearch, component: lazyRouteComponent(effortPages, 'AnalysisPage') });
const burndown = createRoute({ getParentRoute: () => app, path: 'effort/burndown', validateSearch: burndownSearch, component: lazyRouteComponent(effortPages, 'BurndownPage') });

const calendar = createRoute({ getParentRoute: () => app, path: 'calendar', validateSearch: calendarSearch, component: lazyRouteComponent(calendarPages, 'CalendarPage') });
const newLeave = createRoute({ getParentRoute: () => calendar, path: 'leave/new', validateSearch: newLeaveSearch, component: lazyRouteComponent(calendarPages, 'LeaveDialogRoute') });
const editLeave = createRoute({ getParentRoute: () => calendar, path: 'leave/$id', component: lazyRouteComponent(calendarPages, 'LeaveDialogRoute') });
const importLeave = createRoute({ getParentRoute: () => calendar, path: 'import/$step', component: lazyRouteComponent(calendarPages, 'ImportWizardRoute') });

const settings = createRoute({ getParentRoute: () => app, path: 'settings', beforeLoad: () => { throw redirect({ to: '/settings/$tab', params: { tab: 'jira' } }); } });
const settingsTab = createRoute({
  getParentRoute: () => app,
  path: 'settings/$tab',
  beforeLoad: ({ params }) => { if (!isSettingsTab(params.tab)) throw redirect({ to: '/settings/$tab', params: { tab: 'jira' } }); },
  component: lazyRouteComponent(() => import('../features/settings'), 'SettingsPage'),
});

const routeTree = root.addChildren([
  login, noAccess,
  app.addChildren([
  index, myWork, matrixEntry, overview, ankenDetail, matrix, addFunction, phaseSettings, planning, effort, timesheet, analysis, burndown,
  calendar.addChildren([newLeave, editLeave, importLeave]), settings, settingsTab,
  ]),
]);

export function makeRouter(queryClient: QueryClient, history?: RouterHistory) {
  return createRouter({ routeTree, context: { queryClient }, history, defaultPreload: 'intent', scrollRestoration: true });
}

declare module '@tanstack/react-router' {
  interface Register { router: ReturnType<typeof makeRouter> }
}
