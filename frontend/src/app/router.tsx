import type { QueryClient } from '@tanstack/react-query';
import { createRootRouteWithContext, createRoute, createRouter, redirect } from '@tanstack/react-router';
import { z } from 'zod';
import { calendarSearch, CalendarPage, ImportWizardRoute, LeaveDialogRoute, newLeaveSearch } from '../features/calendar';
import { AnalysisPage, analysisSearch, TimesheetPage, timesheetSearch } from '../features/effort';
import { AddFunctionPage, MatrixPage, matrixSearch } from '../features/matrix';
import { MyWorkPage, myWorkSearch } from '../features/my-work';
import { OverviewPage, overviewQuery } from '../features/overview';
import { PlanningPage, planningSearch } from '../features/planning';
import { isSettingsTab, SettingsPage } from '../features/settings';
import { AppShell, NotFound } from './AppShell';

interface Ctx { queryClient: QueryClient }

const root = createRootRouteWithContext<Ctx>()({
  validateSearch: z.object({ ticket: z.string().optional().catch(undefined) }),
  component: AppShell,
  notFoundComponent: NotFound,
});

const index = createRoute({ getParentRoute: () => root, path: '/', beforeLoad: () => { throw redirect({ to: '/my-work' }); } });
const myWork = createRoute({ getParentRoute: () => root, path: 'my-work', validateSearch: myWorkSearch, component: MyWorkPage });

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
const overview = createRoute({ getParentRoute: () => root, path: 'anken', component: OverviewPage });
const ankenDetail = createRoute({ getParentRoute: () => root, path: 'anken/$epic', component: OverviewPage });
const matrix = createRoute({ getParentRoute: () => root, path: 'anken/$epic/matrix', validateSearch: matrixSearch, component: MatrixPage });
const addFunction = createRoute({ getParentRoute: () => root, path: 'anken/$epic/matrix/new', component: AddFunctionPage });

const planning = createRoute({ getParentRoute: () => root, path: 'planning', validateSearch: planningSearch, component: PlanningPage });

const effort = createRoute({ getParentRoute: () => root, path: 'effort', beforeLoad: () => { throw redirect({ to: '/effort/timesheet' }); } });
const timesheet = createRoute({ getParentRoute: () => root, path: 'effort/timesheet', validateSearch: timesheetSearch, component: TimesheetPage });
const analysis = createRoute({ getParentRoute: () => root, path: 'effort/analysis', validateSearch: analysisSearch, component: AnalysisPage });

const calendar = createRoute({ getParentRoute: () => root, path: 'calendar', validateSearch: calendarSearch, component: CalendarPage });
const newLeave = createRoute({ getParentRoute: () => calendar, path: 'leave/new', validateSearch: newLeaveSearch, component: LeaveDialogRoute });
const editLeave = createRoute({ getParentRoute: () => calendar, path: 'leave/$id', component: LeaveDialogRoute });
const importLeave = createRoute({ getParentRoute: () => calendar, path: 'import/$step', component: ImportWizardRoute });

const settings = createRoute({ getParentRoute: () => root, path: 'settings', beforeLoad: () => { throw redirect({ to: '/settings/$tab', params: { tab: 'jira' } }); } });
const settingsTab = createRoute({
  getParentRoute: () => root,
  path: 'settings/$tab',
  beforeLoad: ({ params }) => { if (!isSettingsTab(params.tab)) throw redirect({ to: '/settings/$tab', params: { tab: 'jira' } }); },
  component: SettingsPage,
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
