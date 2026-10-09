import type { QueryClient } from '@tanstack/react-query';
import { createRootRouteWithContext, createRoute, createRouter, lazyRouteComponent, redirect } from '@tanstack/react-router';
import { z } from 'zod';
// Search schemas, the overview query and the tab list are imported directly so each screen's code can load lazily.
import { calendarSearch, newLeaveSearch } from '../features/calendar/search';
import { analysisSearch, timesheetSearch } from '../features/effort/search';
import { matrixSearch } from '../features/matrix/search';
import { myWorkSearch } from '../features/my-work/search';
import { overviewQuery } from '../features/overview/api';
import { planningSearch } from '../features/planning/search';
import { isSettingsTab } from '../features/settings/search';
import { AppShell, NotFound } from './AppShell';

const calendarPages = () => import('../features/calendar');
const effortPages = () => import('../features/effort');
const matrixPages = () => import('../features/matrix');

interface Ctx { queryClient: QueryClient }

const root = createRootRouteWithContext<Ctx>()({
  validateSearch: z.object({ ticket: z.string().optional().catch(undefined) }),
  component: AppShell,
  notFoundComponent: NotFound,
});

const index = createRoute({ getParentRoute: () => root, path: '/', beforeLoad: () => { throw redirect({ to: '/my-work' }); } });
const myWork = createRoute({ getParentRoute: () => root, path: 'my-work', validateSearch: myWorkSearch, component: lazyRouteComponent(() => import('../features/my-work'), 'MyWorkPage') });

/** "Function Matrix" in the sidebar: the first tracked anken that has functions. */
const matrixEntry = createRoute({
  getParentRoute: () => root,
  path: 'matrix',
  beforeLoad: async ({ context }) => {
    const anken = await context.queryClient.ensureQueryData(overviewQuery);
    const first = anken.find((a) => a.has_functions);
    throw first ? redirect({ to: '/anken/$epic/matrix', params: { epic: first.key } }) : redirect({ to: '/anken' });
  },
});
const overview = createRoute({ getParentRoute: () => root, path: 'anken', component: lazyRouteComponent(() => import('../features/overview'), 'OverviewPage') });
const ankenDetail = createRoute({ getParentRoute: () => root, path: 'anken/$epic', component: lazyRouteComponent(() => import('../features/overview'), 'OverviewPage') });
const matrix = createRoute({ getParentRoute: () => root, path: 'anken/$epic/matrix', validateSearch: matrixSearch, component: lazyRouteComponent(matrixPages, 'MatrixPage') });
const addFunction = createRoute({ getParentRoute: () => root, path: 'anken/$epic/matrix/new', component: lazyRouteComponent(matrixPages, 'AddFunctionPage') });

const planning = createRoute({ getParentRoute: () => root, path: 'planning', validateSearch: planningSearch, component: lazyRouteComponent(() => import('../features/planning'), 'PlanningPage') });

const effort = createRoute({ getParentRoute: () => root, path: 'effort', beforeLoad: () => { throw redirect({ to: '/effort/timesheet' }); } });
const timesheet = createRoute({ getParentRoute: () => root, path: 'effort/timesheet', validateSearch: timesheetSearch, component: lazyRouteComponent(effortPages, 'TimesheetPage') });
const analysis = createRoute({ getParentRoute: () => root, path: 'effort/analysis', validateSearch: analysisSearch, component: lazyRouteComponent(effortPages, 'AnalysisPage') });

const calendar = createRoute({ getParentRoute: () => root, path: 'calendar', validateSearch: calendarSearch, component: lazyRouteComponent(calendarPages, 'CalendarPage') });
const newLeave = createRoute({ getParentRoute: () => calendar, path: 'leave/new', validateSearch: newLeaveSearch, component: lazyRouteComponent(calendarPages, 'LeaveDialogRoute') });
const editLeave = createRoute({ getParentRoute: () => calendar, path: 'leave/$id', component: lazyRouteComponent(calendarPages, 'LeaveDialogRoute') });
const importLeave = createRoute({ getParentRoute: () => calendar, path: 'import/$step', component: lazyRouteComponent(calendarPages, 'ImportWizardRoute') });

const settings = createRoute({ getParentRoute: () => root, path: 'settings', beforeLoad: () => { throw redirect({ to: '/settings/$tab', params: { tab: 'jira' } }); } });
const settingsTab = createRoute({
  getParentRoute: () => root,
  path: 'settings/$tab',
  beforeLoad: ({ params }) => { if (!isSettingsTab(params.tab)) throw redirect({ to: '/settings/$tab', params: { tab: 'jira' } }); },
  component: lazyRouteComponent(() => import('../features/settings'), 'SettingsPage'),
});

const routeTree = root.addChildren([
  index, myWork, matrixEntry, overview, ankenDetail, matrix, addFunction, planning, effort, timesheet, analysis,
  calendar.addChildren([newLeave, editLeave, importLeave]), settings, settingsTab,
]);

export function makeRouter(queryClient: QueryClient) {
  return createRouter({ routeTree, context: { queryClient }, defaultPreload: 'intent', scrollRestoration: true });
}

declare module '@tanstack/react-router' {
  interface Register { router: ReturnType<typeof makeRouter> }
}
