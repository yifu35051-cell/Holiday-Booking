Produce & submit a “Requirements Spec” (RS) report（max 4 pages）according to the above descriptions and your own user interview surveys （for any additional features you deem relevant for your system to build）
这句话其实是整个 Stage 1 作业的**灵魂要求**。老师这段话的意思是：

> “不要只照着案例背景（Section 2）抄需求。你们需要自己**假装去做一次用户访谈（User Interview）**，并根据访谈结果，为系统**补充一些你认为合理的新功能（Additional features）**。”

很多同学会疑惑：案例里不是已经写了“搜索房间、预订、支付、管理房间”了吗？为什么还要访谈和加功能？到底怎么操作？

下面为你彻底讲透这背后的逻辑和实操步骤：

### 一、 为什么老师要求做“访谈”和“额外功能”？

1.  **对标课件考点（Week 4 RE）**：软件工程 Week 4 讲了需求获取（Elicitation）的技术，包括 **Interviews（访谈）** 和 **Scenarios（场景）**。老师需要看到你们**实际使用了这些技术**，而不是闭门造车。如果不做访谈，你们写出来的需求就是干巴巴的，没有灵魂。
2.  **区分度（拉开分数）**：如果 30 个小组都只写案例里那 5 个核心组件，大家分数都一样。老师加这句话，是为了看哪个小组能像真正的产品经理一样，去挖掘**真实用户的痛点**，提出**有亮点的额外功能**。
3.  **案例背景太简略**：Section 2 只给了一个宏观框架（搜索、预订、支付）。但在真实的度假村场景中，怎么退订？怎么提醒？怎么管理会员积分？这些都需要通过“访谈”来丰满。

---

### 二、 什么是“Additional Features”（额外功能）？

案例里**明确规定**的核心功能是：
*   客户/会员：搜索房间、预订、支付。
*   会员：注册、付会员费、享受折扣。
*   系统：提前 1 天发邮件提醒。
*   管理员工：添加/删除房间、更新房态、查看订单。

**你们在访谈中可以挖掘出的“额外功能”例子：**
*   **客户端**：用户说“我经常不知道选哪间房，能不能加个**房间图片展示和用户评价（Reviews & Ratings）**？”
*   **员工端**：管理员说“每次手动输入房间号太累了，能不能加个**批量导入 Excel** 的功能？”
*   **系统端**：客户说“如果我临时有事去不了，能不能让我**自己在线取消或修改预订（Cancel/Change Reservation）**？”（*注意：这其实就是 Stage 4 的 CR1 和 CR2，你们可以提前在 Stage 1 埋下伏笔，这叫有前瞻性！*）
*   **营销端**：老板说“我想在节假日发个**优惠券（Promo Codes）**刺激消费。”

---

### 三、 在 4 页纸的 SRS 里，怎么体现“访谈”？

**千万不要在报告里写大段的访谈对话实录（比如：“我问Sarah，你觉得怎么样？她说……”），太占篇幅了。**

你需要把访谈的**结果**浓缩到表格里。核心在于表格的 `Source（需求来源）` 这一列：

| ID | Requirement Description (需求描述) | Priority | Source (来源 - 证明你做过访谈) |
| :--- | :--- | :--- | :--- |
| FR1 | 客户可按日期/房型搜索可用房间。 | Must | **Interview with Customer (Sarah)** & Case Study |
| FR2 | 客户可通过 Alipay/Wechat 支付。 | Must | Case Study |
| **FR3** | **系统应允许客户在线取消或修改预订。** | **Should** | **Interview with Customer (Sarah)** (额外挖掘) |
| **FR4** | **系统应向客户展示房间的实景照片和评价。** | **Could** | **Interview with Customer (Sarah)** (额外挖掘) |
| **FR5** | **管理员可通过上传 Excel 批量添加房间。** | **Should** | **Interview with Resort Manager** (额外挖掘) |

*(注：标粗的 FR3-FR5 就是你们通过访谈得来的 Additional features，这就是加分项！)*

---

### 四、 既然没法真的去访谈，具体怎么做？

你们是学生作业，不需要真的去采访某家真实度假村。你可以：
1.  **做问卷调查（Survey）**：在班级群里发个问卷星：“如果你去度假村旅游，你最希望预订 App 有什么功能？”把收集到的 20 份问卷结果，写成 `Interview/Survey with 20 potential users`。
2.  **角色扮演（Roleplay）**：你们组内 4 个人，分别扮演 **“经常旅游的客户”、“想办会员的精明客户”、“前台管理员工”和“度假村老板”**。互相提问并记录，这就叫模拟访谈。
3.  **写进报告的 User Story 里（模板的 3.4 节）**：
    用敏捷的 User Story 格式把访谈结果包装起来：
    > *“As a Customer (from interview), I want to **cancel my booking online**, so that I don't have to call the front desk when my plans change.”*

### 💡 总结拿分策略：
*   **不要只抄案例**。案例里的功能是基本盘，拿了及格分。
*   **一定要编（或者真实做）一段访谈**，找出 2-3 个合理且实用的 Additional Features。在报告的 FR 表格里用 `Source` 标注出来。这样老师一眼就能看到你们不仅懂理论，还懂用户，直接给高分。
*   结合 Stage 4 的变更请求（CR1/CR2/CR3），如果你们在 Stage 1 的访谈中就把取消、修改、提醒功能挖出来了，你们整个 4 个 Stage 的故事线会极其完美（这叫需求的可追溯性 Traceability）。