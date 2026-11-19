<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, messageOf } from '@/shared/api/client'
import type { ConfigRow } from '@/shared/api/types'

const loading = ref(false)
const saving = ref(false)
const rows = ref<ConfigRow[]>([])
const drafts = ref<Record<string, string>>({})
const category = ref('')

const categoryLabel: Record<string, string> = {
  FACE: '人脸与门禁',
  CHECKIN: '签到',
  AGENT: '智能助手',
  SYSTEM: '系统'
}
const categoryHint: Record<string, string> = {
  FACE: '阈值由 scripts/calibrate.py 标定后写入；editable=0 的项管理端只能看不能改。',
  CHECKIN: '二维码轮换与迟到判定口径。',
  AGENT: '能力级别开关：关掉某一级，模型连该级工具都看不到。',
  SYSTEM: '其它运行参数。'
}

const grouped = computed(() => {
  const map = new Map<string, ConfigRow[]>()
  rows.value.forEach((r) => {
    if (category.value && r.category !== category.value) return
    const list = map.get(r.category) ?? []
    list.push(r)
    map.set(r.category, list)
  })
  return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0]))
})

const dirty = computed(() =>
  rows.value.filter((r) => drafts.value[r.configKey] !== undefined && drafts.value[r.configKey] !== r.configValue)
)

async function load() {
  loading.value = true
  try {
    const list = await api.get<ConfigRow[]>('/api/v1/admin/config', { category: category.value })
    rows.value = list ?? []
    drafts.value = {}
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    loading.value = false
  }
}

function onChange(row: ConfigRow, value: string) {
  drafts.value = { ...drafts.value, [row.configKey]: value }
}

async function save() {
  if (!dirty.value.length) {
    ElMessage.info('没有改动')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将修改 ${dirty.value.length} 项配置：${dirty.value.map((r) => r.configKey).join('、')}。` +
        '阈值类改动会立即影响所有新的签到判定。确定保存吗？',
      '保存配置',
      { type: 'warning' }
    )
  } catch {
    return
  }
  saving.value = true
  try {
    const list = await api.put<ConfigRow[]>('/api/v1/admin/config', {
      items: dirty.value.map((r) => ({ configKey: r.configKey, configValue: drafts.value[r.configKey] }))
    })
    rows.value = list ?? rows.value
    drafts.value = {}
    ElMessage.success('已保存')
  } catch (err) {
    ElMessage.error(messageOf(err))
  } finally {
    saving.value = false
  }
}

function reset() {
  drafts.value = {}
}

/** 值类型决定控件：布尔给开关，其余给文本框，避免把 true 写成 True 再被拒。 */
function isBool(row: ConfigRow) {
  return row.valueType === 'BOOL'
}
function boolValue(row: ConfigRow) {
  return (drafts.value[row.configKey] ?? row.configValue) === 'true'
}
function setBool(row: ConfigRow, v: boolean) {
  onChange(row, v ? 'true' : 'false')
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <h2>系统配置</h2>
      <div>
        <el-button :disabled="!dirty.length" @click="reset">放弃改动</el-button>
        <el-button type="primary" :disabled="!dirty.length" :loading="saving" @click="save">
          保存{{ dirty.length ? `（${dirty.length}）` : '' }}
        </el-button>
      </div>
    </div>

    <el-card shadow="never" v-loading="loading">
      <div class="filters">
        <el-radio-group v-model="category" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="FACE">人脸与门禁</el-radio-button>
          <el-radio-button value="CHECKIN">签到</el-radio-button>
          <el-radio-button value="AGENT">智能助手</el-radio-button>
          <el-radio-button value="SYSTEM">系统</el-radio-button>
        </el-radio-group>
      </div>

      <el-empty v-if="!grouped.length" description="没有配置项" />

      <div v-for="[cat, list] in grouped" :key="cat" class="group">
        <div class="group-head">
          <b>{{ categoryLabel[cat] || cat }}</b>
          <span class="hint">{{ categoryHint[cat] || '' }}</span>
        </div>
        <el-table :data="list" size="small" border>
          <el-table-column prop="configKey" label="键" width="230">
            <template #default="{ row }">
              <span class="mono">{{ row.configKey }}</span>
              <el-tag v-if="!row.editable" size="small" type="info" style="margin-left: 6px">只读</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="description" label="说明" min-width="230">
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </el-table-column>
          <el-table-column prop="valueType" label="类型" width="90" />
          <el-table-column label="值" width="260">
            <template #default="{ row }">
              <el-switch
                v-if="isBool(row)"
                :model-value="boolValue(row)"
                :disabled="!row.editable"
                @update:model-value="(v: any) => setBool(row, !!v)"
              />
              <el-input
                v-else
                :model-value="drafts[row.configKey] ?? row.configValue"
                :disabled="!row.editable"
                size="small"
                @update:model-value="(v: string) => onChange(row, v)"
              />
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="170">
            <template #default="{ row }">
              {{ (row.updatedAt || '').replace('T', ' ').slice(0, 19) || '—' }}
            </template>
          </el-table-column>
        </el-table>
      </div>

      <el-alert type="info" :closable="false" show-icon style="margin-top: 14px">
        人脸阈值（PASS_THRESHOLD 等）已由标定脚本写入并锁定为只读 —— 手工改动会直接影响误拒率，
        需要重标定时请走 <code>scripts/calibrate.py</code> 而不是这一页。
      </el-alert>
    </el-card>
  </div>
</template>

<style scoped>
.filters {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.group {
  margin-bottom: 18px;
}
.group-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.hint {
  font-size: 12px;
  color: #909399;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
</style>
