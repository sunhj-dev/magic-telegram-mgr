import Vue from 'vue';
import Router from 'vue-router';

import Dashboard from '@/views/Dashboard.vue';
import Accounts from '@/views/Accounts.vue';
import Messages from '@/views/Messages.vue';
import MassMessage from '@/views/MassMessage.vue';
import Settings from '@/views/Settings.vue';

Vue.use(Router);

export default new Router({
  mode: 'history',
  routes: [
    { path: '/', redirect: '/accounts' },
    { path: '/dashboard', name: 'Dashboard', component: Dashboard },
    { path: '/accounts', name: 'Accounts', component: Accounts },
    { path: '/messages', name: 'Messages', component: Messages },
    { path: '/mass-message', name: 'MassMessage', component: MassMessage },
    { path: '/settings', name: 'Settings', component: Settings }
  ]
});
