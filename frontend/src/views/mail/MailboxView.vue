<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { mailApi, managementApi } from '@/api/services'
import type { Folder, Mail, MailInput, Page } from '@/api/types'
import { commandKey } from '@/api/client'
import { useAuth } from '@/stores/auth'
import { useTask } from '@/composables/useTask'
import { label, time } from '@/shared/format'
import TaskNotice from '@/components/TaskNotice.vue'
import PageNav from '@/components/PageNav.vue'
const props = defineProps<{ administrative?: boolean }>(),
  auth = useAuth()
const { busy, error, success, run } = useTask(),
  folder = ref<Folder>('INBOX'),
  unreadOnly = ref(false),
  unread = ref(0)
const data = ref<Page<Mail>>({ items: [], page: 0, size: 20, total: 0 }),
  selected = ref<Mail | null>(null),
  composing = ref(false),
  acknowledge = ref(false),
  recipientName = ref('')
const filters = reactive({ senderEmail: '', recipientEmail: '', state: '' })
const draft = reactive<MailInput>({ recipientEmail: '', subject: '', body: '' })
let key = commandKey()
async function load(page = data.value.page) {
  data.value = await mailApi.list(
    props.administrative
      ? { ...filters, page }
      : { folder: folder.value, unreadOnly: folder.value === 'INBOX' && unreadOnly.value, page },
    props.administrative,
  )
  if (!props.administrative) unread.value = (await mailApi.unread()).count
}
onMounted(() => run(() => load(0)))
function changeFolder(next: Folder) {
  void run(async () => {
    folder.value = next
    selected.value = null
    await load(0)
  })
}
function open(id: string) {
  void run(async () => {
    selected.value = await mailApi.get(id, props.administrative)
    acknowledge.value = false
  })
}
function compose(reply?: Mail) {
  draft.recipientEmail = reply?.senderEmail ?? ''
  draft.subject = reply ? `Re: ${reply.subject}`.slice(0, 200) : ''
  draft.body = ''
  draft.replyToId = reply?.id
  recipientName.value = ''
  composing.value = true
  key = commandKey()
}
function send() {
  void run(async () => {
    const message = await mailApi.send({ ...draft }, key(draft))
    composing.value = false
    folder.value = 'SENT'
    selected.value = message
    await load(0)
    success.value = '邮件已进入发送队列，等待邮件服务器接受后将进入对方收件箱。'
  })
}
function visibility(part: 'INBOX' | 'SENT', deleted: boolean) {
  void run(
    async () => {
      if (!selected.value) return
      await mailApi.visibility(selected.value.id, part, deleted)
      selected.value = await mailApi.get(selected.value.id)
      await load()
    },
    deleted ? '已移入你的回收站。' : '已恢复你的副本。',
  )
}
function retry() {
  void run(async () => {
    if (!selected.value) return
    if (selected.value.kind === 'SYSTEM')
      await managementApi.retryNotification(selected.value.id.slice(1), acknowledge.value)
    else await mailApi.retry(selected.value.id, acknowledge.value, props.administrative)
    selected.value = await mailApi.get(selected.value.id, props.administrative)
    await load()
  }, '已重新进入投递队列。')
}

function applyFilters() {
  void run(async () => {
    selected.value = null
    await load(0)
  })
}

function verifyRecipient() {
  void run(async () => {
    recipientName.value = (await mailApi.recipient(draft.recipientEmail)).displayName
  })
}

function toggleRead() {
  void run(async () => {
    selected.value = await mailApi.read(selected.value!.id, !selected.value!.readAt)
    await load()
  })
}
</script>
<template>
  <div class="page-heading">
    <div>
      <div class="eyebrow">{{ administrative ? 'MAIL OVERSIGHT' : 'YOUR MAILBOX' }}</div>
      <h1>{{ administrative ? '全站邮件' : '我的邮箱' }}</h1>
      <p>
        {{
          administrative
            ? '全站个人邮件与系统通知；查看正文会记录审计。'
            : auth.user?.email + ' · ' + unread + ' 封未读'
        }}
      </p>
    </div>
    <div class="row">
      <button :disabled="busy" @click="run(() => load())">刷新</button
      ><button v-if="!administrative" class="primary" :disabled="busy" @click="compose()">
        ＋ 写邮件
      </button>
    </div>
  </div>
  <TaskNotice :busy="busy" :error="error" :success="success" />
  <form v-if="administrative" class="panel toolbar" @submit.prevent="applyFilters">
    <label>发件邮箱<input v-model="filters.senderEmail" placeholder="精确邮箱 / SYSTEM" /></label
    ><label>收件邮箱<input v-model="filters.recipientEmail" type="email" /></label
    ><label
      >状态<select v-model="filters.state">
        <option value="">全部</option>
        <option
          v-for="state in [
            'READY',
            'RETRY',
            'SENDING',
            'SENT',
            'SIMULATED',
            'UNKNOWN',
            'FAILED',
            'SKIPPED',
          ]"
          :key="state"
          :value="state"
        >
          {{ label(state) }}
        </option>
      </select></label
    ><button :disabled="busy">筛选</button>
  </form>
  <div v-else class="tabs">
    <button
      v-for="item in [
        { value: 'INBOX', title: '收件箱' },
        { value: 'SENT', title: '发件箱' },
        { value: 'TRASH', title: '回收站' },
      ]"
      :key="item.value"
      :class="{ selected: folder === item.value }"
      :disabled="busy"
      @click="changeFolder(item.value as Folder)"
    >
      {{ item.title }}</button
    ><label v-if="folder === 'INBOX'" class="check"
      ><input
        v-model="unreadOnly"
        type="checkbox"
        :disabled="busy"
        @change="run(() => load(0))"
      />仅未读</label
    >
  </div>
  <form v-if="composing && !administrative" class="panel" @submit.prevent="send">
    <div class="row spread">
      <h3>{{ draft.replyToId ? '回复邮件 ' + draft.replyToId : '新邮件' }}</h3>
      <button type="button" :disabled="busy" @click="composing = false">收起</button>
    </div>
    <fieldset :disabled="busy">
      <label
        >收件邮箱<input
          v-model="draft.recipientEmail"
          type="email"
          required
          maxlength="254"
          :readonly="!!draft.replyToId"
          @input="recipientName = ''"
      /></label>
      <div class="row">
        <button type="button" @click="verifyRecipient">核对收件账户</button
        ><span>{{ recipientName }}</span>
      </div>
      <label>主题<input v-model="draft.subject" required maxlength="200" /></label
      ><label>正文<textarea v-model="draft.body" required maxlength="10000" rows="8" /></label>
      <div class="row spread">
        <small>{{ draft.body.length }} / 10000 字符 · 纯文本</small
        ><button class="primary">发送邮件 →</button>
      </div>
    </fieldset>
  </form>
  <div class="mail-layout">
    <section class="panel">
      <p v-if="!data.items.length" class="empty">当前文件夹暂无邮件。</p>
      <button
        v-for="message in data.items"
        :key="message.id"
        class="mail-item"
        :class="{ selected: selected?.id === message.id }"
        :disabled="busy"
        @click="open(message.id)"
      >
        <strong
          >{{ !message.readAt && folder === 'INBOX' && !administrative ? '● ' : ''
          }}{{ message.subject }}</strong
        ><small>{{ message.senderEmail }} → {{ message.recipientEmail }}</small
        ><small>{{ time(message.createdAt) }} · {{ label(message.state) }}</small></button
      ><PageNav
        :page="data.page"
        :total="data.total"
        :count="data.items.length"
        :busy="busy"
        @change="(page) => run(() => load(page))"
      />
    </section>
    <section class="panel">
      <template v-if="selected"
        ><span class="pill">{{ label(selected.state) }} · {{ selected.id }}</span>
        <h2>{{ selected.subject }}</h2>
        <p class="hint">
          发件人 {{ selected.senderEmail }}<br />收件人 {{ selected.recipientEmail }}<br />创建
          {{ time(selected.createdAt) }} · 接受 {{ time(selected.deliveredAt) }}<br />已读
          {{ time(selected.readAt)
          }}<span v-if="selected.replyToId"> · 回复 {{ selected.replyToId }}</span>
        </p>
        <div class="mail-body">{{ selected.body }}</div>
        <div class="row" v-if="!administrative">
          <template v-if="selected.recipientId === auth.user?.id && selected.deliveredAt"
            ><button :disabled="busy" @click="toggleRead">
              标为{{ selected.readAt ? '未读' : '已读' }}</button
            ><button
              v-if="selected.kind === 'PERSONAL'"
              :disabled="busy"
              @click="compose(selected)"
            >
              回复</button
            ><button :disabled="busy" @click="visibility('INBOX', !selected.recipientDeleted)">
              {{ selected.recipientDeleted ? '恢复收件副本' : '删除收件副本' }}
            </button></template
          ><button
            v-if="selected.senderId === auth.user?.id"
            :disabled="busy"
            @click="visibility('SENT', !selected.senderDeleted)"
          >
            {{ selected.senderDeleted ? '恢复发件副本' : '删除发件副本' }}
          </button>
        </div>
        <p v-if="administrative" class="hint">
          发送方删除：{{ selected.senderDeleted ? '是' : '否' }} · 收件方删除：{{
            selected.recipientDeleted ? '是' : '否'
          }}
        </p>
        <section
          v-if="
            ['FAILED', 'UNKNOWN'].includes(selected.state) &&
            (administrative || selected.senderId === auth.user?.id)
          "
        >
          <hr class="divider" />
          <p>尝试 {{ selected.attempts }} 次 · {{ selected.lastError }}</p>
          <label v-if="selected.state === 'UNKNOWN'" class="check"
            ><input
              v-model="acknowledge"
              type="checkbox"
            />我已核对投递情况，接受可能重复发送的风险</label
          ><button
            :disabled="busy || (selected.state === 'UNKNOWN' && !acknowledge)"
            @click="retry"
          >
            重新投递
          </button>
        </section></template
      >
      <p v-else class="empty">选择一封邮件查看正文。</p>
    </section>
  </div>
</template>
