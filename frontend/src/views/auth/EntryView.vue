<template>
  <main class="entry">
    <div class="eyebrow">HOLIDAY BOOKING</div>
    <h1>把时光，留给假日。</h1>
    <p>选择你的专属入口，开启今天的旅程。</p>
    <div class="entry-grid">
      <RouterLink to="/login/customer" class="entry-card"
        ><span>01 / STAY</span>
        <h2>顾客入口</h2>
        <p>探索客房 · 管理旅程 · 会员礼遇</p>
        <b>开始旅程 →</b></RouterLink
      ><RouterLink to="/login/staff" class="entry-card"
        ><span>02 / SERVICE</span>
        <h2>员工入口</h2>
        <p>预订服务 · 客房维护 · 业务通知</p>
        <b>进入员工工作台 →</b></RouterLink
      ><RouterLink to="/login/admin" class="entry-card"
        ><span>03 / MANAGEMENT</span>
        <h2>管理员入口</h2>
        <p>经营配置 · 账户管理 · 全站审计</p>
        <b>进入管理中心 →</b></RouterLink
      >
    </div>
    <footer>HOLIDAY · 让入住更简单</footer>
  </main>
</template>
