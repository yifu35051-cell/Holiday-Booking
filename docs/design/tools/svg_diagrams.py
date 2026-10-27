"""Offline SVG views of the five Mermaid blocks used in this document.

The small parser intentionally supports this document's relationship syntax only.
Node placement is explicit so the diagrams need no network, Node or browser runtime.
Keep Mermaid source and these placements in sync when changing the design.
"""

from __future__ import annotations

import html
import re
from pathlib import Path


def esc(value: str) -> str:
    return html.escape(value, quote=True)


class Drawing:
    def __init__(self, width: int, height: int, title: str, tag: str):
        self.tag = tag
        self.parts = [f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" role="img" aria-labelledby="{tag}-title">
<title id="{tag}-title">{esc(title)}</title>
<defs>
<marker id="{tag}-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0L10 5L0 10Z" fill="#557180"/></marker>
<marker id="{tag}-triangle" viewBox="0 0 12 12" refX="11" refY="6" markerWidth="10" markerHeight="10" orient="auto"><path d="M1 1L11 6L1 11Z" fill="white" stroke="#557180"/></marker>
</defs><rect width="100%" height="100%" fill="white"/>
<style>#{tag}-canvas text{{font-family:"Microsoft YaHei","Segoe UI",Arial,sans-serif;fill:#173747}} #{tag}-canvas .edge-label{{font-size:12px;paint-order:stroke;stroke:white;stroke-width:5px;stroke-linejoin:round}}</style><g id="{tag}-canvas">''']

    def text(self, x: float, y: float, value: str, size: int = 15, weight: int = 400, anchor: str = 'middle', extra: str = ''):
        self.parts.append(f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-size="{size}" font-weight="{weight}" {extra}>{esc(value)}</text>')

    def box(self, x: float, y: float, label: str, subtitle: str = '', width: int = 218, height: int = 72, color: str = '#edf5f7'):
        self.parts.append(f'<rect x="{x-width/2}" y="{y-height/2}" width="{width}" height="{height}" rx="10" fill="{color}" stroke="#a6bcc7" stroke-width="1.5"/>')
        self.text(x, y - 3 if subtitle else y + 5, label, 15, 600)
        if subtitle:
            self.text(x, y + 21, subtitle, 12)

    def line(self, points, label: str = '', label_pos=None, dashed: bool = False, triangle: bool = False):
        coords = ' '.join(f'{x},{y}' for x, y in points)
        dash = ' stroke-dasharray="6 4"' if dashed else ''
        marker = f'{self.tag}-triangle' if triangle else f'{self.tag}-arrow'
        self.parts.append(f'<polyline points="{coords}" fill="none" stroke="#557180" stroke-width="1.6"{dash} marker-end="url(#{marker})"/>')
        if label:
            x, y = label_pos or ((points[0][0]+points[-1][0])/2, (points[0][1]+points[-1][1])/2-8)
            self.text(x, y, label, 12, extra='class="edge-label"')

    def finish(self) -> str:
        return ''.join(self.parts) + '</g></svg>'


def endpoints(start, end, width=218, height=72):
    x1, y1 = start
    x2, y2 = end
    dx, dy = x2-x1, y2-y1
    if abs(dx) > abs(dy)*1.3:
        sign = 1 if dx > 0 else -1
        return [(x1 + sign*width/2, y1), (x2 - sign*width/2, y2)]
    sign = 1 if dy > 0 else -1
    return [(x1, y1 + sign*height/2), (x2, y2 - sign*height/2)]


def architecture(code: str, title: str, tag: str) -> str:
    d = Drawing(1160, 740, title, tag)
    labels = {
        'U': ('浏览器', '顾客 / 会员 / 管理人员'),
        'N': ('Nginx', 'HTTPS / 同域代理 / 静态资源'),
        'V': ('Vue 3 前端', '顾客界面 + 管理界面'),
        'A': ('Java / Spring Boot', 'REST API + 业务模块 + 调度'),
        'DB': ('MySQL', '业务 / 库存 / 会话 / 任务 / 审计'),
        'P': ('PaymentGateway', '支付宝沙箱 / Mock / 微信扩展'),
        'E': ('EmailGateway', 'DirectMail / 本地 Mailpit'),
    }
    pos = {'U': (575, 70), 'N': (575, 220), 'V': (185, 385), 'A': (575, 385),
           'DB': (185, 610), 'P': (575, 610), 'E': (965, 610)}
    for line in code.splitlines():
        match = re.fullmatch(r'\s*(\w+) -->\s*(?:\|([^|]+)\|\s*)?(\w+)\s*', line)
        if not match:
            continue
        a, label, b = match.groups()
        if (a, b) == ('A', 'P'):
            points = [(550, 421), (550, 574)]
            d.line(points, '创建 / 查询', (505, 500))
        elif (a, b) == ('P', 'A'):
            d.line([(600, 574), (600, 421)], label or '', (650, 530))
        else:
            d.line(endpoints(pos[a], pos[b], 250, 80), label or '')
    for name, xy in pos.items():
        d.box(*xy, *labels[name], width=250, height=80)
    d.text(575, 710, '应用运行时调用端口；外部支付与邮件调用不占用数据库事务。', 14)
    return d.finish()


def sequence(code: str, title: str, tag: str) -> str:
    participants = []
    arrows = []
    for line in code.splitlines():
        declaration = re.fullmatch(r'\s*(?:actor|participant) (\w+) as (.+)', line)
        if declaration:
            participants.append(declaration.groups())
        arrow = re.fullmatch(r'\s*(\w+)(-->>|->>)(\w+):\s*(.+)', line)
        if arrow:
            arrows.append(arrow.groups())
    spacing = 230
    positions = {name: 110+i*spacing for i, (name, _) in enumerate(participants)}
    height = 150 + len(arrows)*64
    d = Drawing(1140, height, title, tag)
    for name, label in participants:
        x = positions[name]
        d.parts.append(f'<line x1="{x}" y1="90" x2="{x}" y2="{height-30}" stroke="#b8c7cf" stroke-dasharray="5 5"/>')
        # Long service name is shortened only for the SVG header.
        if name == 'APP':
            label = '预订 / 支付应用服务'
        d.box(x, 48, label, width=208, height=58)
    for i, (a, arrow, b, label) in enumerate(arrows):
        x1, x2, y = positions[a], positions[b], 130+i*64
        if a == b:
            d.line([(x1, y), (x1+90, y), (x1+90, y+25), (x1, y+25)],
                   label, (x1+40, y-10), dashed=arrow == '-->>')
        else:
            # Wrap long Chinese messages at a safe display width.
            if label == 'POST /bookings + Idempotency-Key':
                d.text((x1+x2)/2, y-29, 'POST /bookings', 12, extra='class="edge-label"')
                label = 'Idempotency-Key'
            elif len(label) > 23:
                cut = 22
                d.text((x1+x2)/2, y-29, label[:cut], 12, extra='class="edge-label"')
                label = label[cut:]
            d.line([(x1, y), (x2, y)], label, ((x1+x2)/2, y-10), dashed=arrow == '-->>')
    return d.finish()


def state(code: str, title: str, tag: str) -> str:
    d = Drawing(1080, 810, title, tag)
    pos = {'PENDING_PAYMENT': (285, 130), 'EXPIRING': (795, 290),
           'CONFIRMED': (285, 460), 'EXPIRED': (795, 460),
           'COMPLETED': (285, 705), 'CANCELLED': (795, 705)}
    subtitles = {'PENDING_PAYMENT': '待付款 / 已锁房', 'EXPIRING': '关单与付款结果核实中',
                 'CONFIRMED': '已确认 / 库存 BOOKED', 'EXPIRED': '确认未付款后释放库存',
                 'COMPLETED': '住宿日期过后的归档', 'CANCELLED': 'CR1 取消；退款状态单独跟踪'}
    d.parts.append('<circle cx="285" cy="32" r="8" fill="#173747"/>')
    for line in code.splitlines():
        m = re.fullmatch(r'\s*(\[\*\]|\w+) --> (\w+):\s*(.+)', line)
        if not m:
            continue
        a, b, label = m.groups()
        if a == '[*]':
            d.line([(285, 40), (285, 94)], '', None)
        elif (a, b) == ('PENDING_PAYMENT', 'CANCELLED'):
            d.line([(176, 130), (65, 130), (65, 770), (795, 770), (795, 741)], label, (500, 793))
        elif (a, b) == ('EXPIRING', 'CONFIRMED'):
            d.line([(686, 290), (550, 290), (550, 460), (394, 460)], label, (585, 383))
        elif (a, b) == ('PENDING_PAYMENT', 'CONFIRMED'):
            d.line([(285, 166), (285, 424)], label, (285, 340))
        elif (a, b) == ('CONFIRMED', 'CANCELLED'):
            d.line([(394, 460), (670, 705)], label, (540, 575))
        else:
            d.line(endpoints(pos[a], pos[b]), label)
    for name, xy in pos.items():
        d.box(*xy, name, subtitles[name], width=250 if name == 'CANCELLED' else 218)
    return d.finish()


def relationship(code: str, title: str, tag: str, is_er: bool) -> str:
    if is_er:
        pos = {'APP_USER': (515, 65), 'MEMBERSHIP': (145, 235), 'MEMBERSHIP_ORDER': (900, 235),
               'BOOKING': (515, 235), 'BOOKING_REVISION': (515, 415), 'BOOKING_LINE': (515, 600),
               'ROOM_TYPE': (145, 415), 'ROOM': (145, 600), 'ROOM_NIGHT': (145, 795),
               'ROOM_RATE': (145, 235), 'BOOKING_NIGHTLY_PRICE': (515, 795),
               'PAYMENT_ORDER': (900, 600), 'PAYMENT_ATTEMPT': (900, 795),
               'REFUND': (900, 990), 'NOTIFICATION_TASK': (515, 990)}
        # Membership and rates occupy separate upper lanes.
        pos['MEMBERSHIP'] = (145, 65)
        height = 1100
        width = 1120
    else:
        pos = {'UserAccount': (490, 65), 'Membership': (130, 235), 'MembershipOrder': (850, 235),
               'Booking': (490, 235), 'BookingRevision': (490, 415), 'BookingLine': (490, 600),
               'RoomType': (130, 415), 'Room': (130, 600), 'RoomNight': (130, 795),
               'NightlyPrice': (490, 795), 'PaymentOrder': (850, 600), 'PaymentAttempt': (850, 795),
               'Refund': (850, 990), 'NotificationTask': (490, 990),
               'PaymentGateway': (355, 1180), 'AlipayGateway': (130, 1360),
               'MockPaymentGateway': (355, 1360), 'WechatGateway': (580, 1360),
               'EmailGateway': (850, 1180), 'SmtpEmailGateway': (850, 1360)}
        height = 1450
        width = 1080
    d = Drawing(width, height, title, tag)
    nodes_used = set()
    cardinality = {'||': '1', 'o|': '0..1', '|o': '0..1', 'o{': '0..N', '|{': '1..N'}
    for line in code.splitlines():
        if is_er:
            m = re.fullmatch(r'\s*(\w+) ([|o]+)--([|o{}]+) (\w+) : (.+)', line)
            if not m:
                continue
            a, c1, c2, b, name = m.groups()
            c1, c2 = cardinality[c1], cardinality[c2]
            inherit = False
        else:
            m = re.fullmatch(r'\s*(\w+) "([^"]+)" (?:-->|\*--) "([^"]+)" (\w+) : (.+)', line)
            inheritance = re.fullmatch(r'\s*(\w+) <\|\.\. (\w+)', line)
            if inheritance:
                b, a = inheritance.groups()
                c1, c2, name, inherit = '', '', 'implements', True
            elif m:
                a, c1, c2, b, name = m.groups()
                inherit = False
            else:
                continue
        nodes_used.update([a, b])
        label = name if inherit else f'{c1} / {c2} · {name}'
        if a in ('BookingRevision', 'BOOKING_REVISION') and b in ('NotificationTask', 'NOTIFICATION_TASK'):
            x1, y1 = pos[a]
            x2, y2 = pos[b]
            route_x = 320 if is_er else 310
            d.line([(x1-100, y1), (route_x, y1), (route_x, y2), (x2-100, y2)], label, (route_x, 915))
        elif a in ('Booking', 'BOOKING') and b in ('PaymentOrder', 'PAYMENT_ORDER'):
            x1, y1 = pos[a]
            x2, y2 = pos[b]
            route_x = 710 if is_er else 685
            d.line([(x1+100, y1), (route_x, y1), (route_x, y2), (x2-100, y2)], label, (route_x, 505))
        else:
            d.line(endpoints(pos[a], pos[b], 200, 72), label, dashed=inherit, triangle=inherit)
    if nodes_used != set(pos):
        raise ValueError(f'Diagram nodes changed: {nodes_used ^ set(pos)}')
    for name, xy in pos.items():
        d.box(*xy, name, width=238 if len(name) > 20 else 200, height=72,
              color='#f2f6fa' if 'Gateway' in name else '#edf5f7')
    if is_er:
        d.text(width/2, height-30, '两种业务关联二选一；字段、外键和 CHECK 约束以第 10 章为准。', 14)
    else:
        d.text(width/2, height-25, '关系摘要；完整属性与方法见第 9.2 节，组合关系见 Mermaid 源码。', 13)
    return d.finish()


def render_all(source: str, out_dir: Path, titles: list[str]) -> list[Path]:
    blocks = re.findall(r'^```mermaid\n(.*?)\n```', source, re.M | re.S)
    if len(blocks) != 5 or len(titles) != 5:
        raise ValueError('Expected the five documented Mermaid blocks.')
    out_dir.mkdir(exist_ok=True)
    paths = []
    for i, (code, title) in enumerate(zip(blocks, titles), 1):
        tag = f'hb-diagram-{i}'
        if i == 1:
            svg = architecture(code, title, tag)
        elif i == 2:
            svg = sequence(code, title, tag)
        elif i == 3:
            svg = state(code, title, tag)
        else:
            svg = relationship(code, title, tag, is_er=i == 5)
        path = out_dir / f'diagram-{i:02d}.svg'
        path.write_text(svg, encoding='utf-8')
        paths.append(path)
        print(f'Built offline SVG {i}: {title}', flush=True)
    return paths
