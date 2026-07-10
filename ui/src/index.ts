import { definePlugin } from '@halo-dev/ui-shared'
import { markRaw } from 'vue'
import RiFireLine from '~icons/ri/fire-line'

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/fireside',
        name: 'Fireside',
        component: () => import(/* webpackChunkName: "fireside" */ './views/FiresideView.vue'),
        meta: {
          title: '围炉',
          searchable: true,
          permissions: ['plugin:fireside:manage'],
          menu: {
            name: '围炉',
            group: 'content',
            icon: markRaw(RiFireLine),
            priority: 52,
          },
        },
      },
    },
  ],
  extensionPoints: {},
})
