<script setup>
defineProps({
  tone: { type: String, default: 'neutral' }, // pass | fail | warn | neutral
  label: { type: String, required: true },
})
</script>

<template>
  <span class="stamp" :data-tone="tone">{{ label }}</span>
</template>

<style scoped>
.stamp {
  display: inline-block;
  padding: 1px 7px;
  border: 1.5px solid currentColor;
  border-radius: 2px;
  font-family: var(--mono);
  font-size: 11px;
  font-weight: 700;
  line-height: 1.6;
  letter-spacing: 0.06em;
  white-space: nowrap;
}

.stamp[data-tone="pass"] {
  color: var(--green);
  background: rgba(15, 123, 95, 0.06);
}

.stamp[data-tone="fail"] {
  color: var(--red);
  background: rgba(180, 35, 24, 0.06);
}

.stamp[data-tone="warn"] {
  color: var(--amber);
  background: rgba(181, 71, 8, 0.07);
}

.stamp[data-tone="neutral"] {
  color: var(--ink-soft);
  background: rgba(22, 32, 43, 0.04);
}
</style>
