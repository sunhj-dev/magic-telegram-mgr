import axios from 'axios';

const http = axios.create({
  baseURL: '/api',
  timeout: 15000
});

http.interceptors.response.use(
  res => res.data,
  error => Promise.reject(error)
);

const api = {
  dashboard: {
    getStats() {
      return http.get('/admin/stats');
    }
  },
  accounts: {
    getList(params) {
      // 使用 POST /admin/accounts/list，避免 Spring 6 对参数名的限制问题
      return http.post('/admin/accounts/list', {
        page: params?.page ?? 0,
        size: params?.size ?? 10,
        search: params?.search || ''
      });
    },
    create(data) {
      return http.post('/telegram/account/create', data);
    }
  },
  messages: {
    getList(params) {
      // 同样走 WebAdminController 中的 POST /admin/messages/list
      return http.post('/admin/messages/list', {
        page: params?.page ?? 0,
        size: params?.size ?? 10,
        search: params?.search || '',
        chatId: params?.chatId || null,
        messageType: params?.type || params?.messageType || null,
        date: params?.date || null
      });
    }
  },
  massMessage: {
    getTasks(params) {
      // 对应 MassMessageController: @RequestMapping(\"/admin/mass-message\") + @GetMapping(\"/tasks\")
      return http.get('/admin/mass-message/tasks', { params });
    },
    createTask(data) {
      return http.post('/admin/mass-message/task', data);
    },
    startTask(id) {
      return http.post(`/admin/mass-message/task/${id}/start`);
    },
    pauseTask(id) {
      return http.post(`/admin/mass-message/task/${id}/pause`);
    },
    deleteTask(id) {
      return http.delete(`/admin/mass-message/task/${id}`);
    },
    getTaskDetail(id) {
      return http.get(`/admin/mass-message/task/${id}`);
    }
  },
  telegram: {
    config(data) {
      return http.post('/telegram/config', data);
    },
    resetSession(phoneNumber) {
      return http.delete('/telegram/session/clear', { params: { phoneNumber } });
    },
    sendCode(phoneNumber) {
      return http.post('/telegram/auth/phone', { phoneNumber });
    },
    checkCode(data) {
      return http.post('/telegram/auth/code', data);
    },
    submitPassword(data) {
      return http.post('/telegram/auth/password', data);
    }
  }
};

export default api;
