"""Vector diagrams for the dissertation; diagrams describe inspected code, not results."""
from pathlib import Path
from reportlab.pdfgen import canvas
from reportlab.lib.colors import HexColor

HERE = Path(__file__).resolve().parent
INK = HexColor('#172536')
SUB = HexColor('#425369')
FAINT = HexColor('#e6edf3')
TEAL = HexColor('#117a7a')
BLUE = HexColor('#254f8a')
PURPLE = HexColor('#665288')
ORANGE = HexColor('#ad6139')
WHITE = HexColor('#ffffff')
PAPER = HexColor('#f8fafc')


def txt(c, x, y, value, size: float = 10.0, color=INK, font='Helvetica'):
    c.setFont(font, size)
    c.setFillColor(color)
    c.drawString(x, y, value)


def box(c, x, y, w, h, fill=PAPER, stroke=FAINT, radius=9):
    c.setStrokeColor(stroke)
    c.setLineWidth(0.8)
    c.setFillColor(fill)
    c.roundRect(x, y, w, h, radius, stroke=1, fill=1)


def card(c, x, y, w, h, accent, label, lines):
    box(c, x, y, w, h, WHITE)
    c.setFillColor(accent)
    c.roundRect(x + 11, y + h - 15, 20, 3, 1.5, fill=1, stroke=0)
    txt(c, x + 11, y + h - 32, label, 11, INK, 'Helvetica-Bold')
    for i, line in enumerate(lines):
        txt(c, x + 11, y + h - 48 - i * 14, line, 9.4, SUB)


def arrow(c, x1, y, x2, color=TEAL, dashed=False):
    c.setStrokeColor(color)
    c.setFillColor(color)
    c.setLineWidth(1.7)
    c.setDash(3, 3) if dashed else c.setDash()
    c.line(x1, y, x2 - 7, y)
    c.setDash()
    p = c.beginPath()
    p.moveTo(x2 - 8, y + 4)
    p.lineTo(x2, y)
    p.lineTo(x2 - 8, y - 4)
    p.close()
    c.drawPath(p, fill=1, stroke=0)


def architecture():
    c = canvas.Canvas(str(HERE / 'architecture.pdf'), pagesize=(720, 420), pageCompression=1)
    c.setTitle('Sentinel KMS: knowledge-assisted incident response')
    txt(c, 25, 385, 'KNOWLEDGE-ASSISTED INCIDENT RESPONSE', 11, TEAL, 'Helvetica-Bold')
    txt(c, 25, 362, 'From incident context to source-based guidance', 18, INK, 'Helvetica-Bold')
    c.setStrokeColor(FAINT)
    c.line(25, 345, 695, 345)

    box(c, 25, 69, 166, 259)
    box(c, 211, 69, 287, 259)
    box(c, 519, 69, 176, 259)
    txt(c, 36, 306, '01  ANALYST CONSOLE', 10, BLUE, 'Helvetica-Bold')
    txt(c, 222, 306, '02  SPRING APPLICATION', 10, TEAL, 'Helvetica-Bold')
    txt(c, 530, 306, '03  EXTERNAL BOUNDARIES', 10, PURPLE, 'Helvetica-Bold')

    card(c, 36, 237, 144, 55, BLUE, 'Knowledge library', ['Locate and read articles'])
    card(c, 36, 176, 144, 55, BLUE, 'Ingestion console', ['Submit and inspect jobs'])
    card(c, 36, 115, 144, 55, BLUE, 'Incident guidance', ['Explain and review advice'])

    card(c, 222, 237, 265, 55, TEAL, 'Governance and lifecycle', ['Active membership, publication and revision checks'])
    card(c, 222, 176, 265, 55, TEAL, 'Durable ingestion', ['Retained sources, leased claims, fenced indexing'])
    card(c, 222, 115, 265, 55, TEAL, 'Grounded answer path', ['Authorized hybrid search, citation binding'])

    card(c, 530, 226, 154, 66, PURPLE, 'PostgreSQL', ['Articles, revisions, jobs,', 'pgvector, graph assertions'])
    card(c, 530, 150, 154, 66, PURPLE, 'Identity service', ['Signed bearer tokens;', 'workspace roles in DB'])
    card(c, 530, 78, 154, 66, ORANGE, 'Model providers', ['Chat + embeddings may be', 'local or hosted'])

    arrow(c, 181, 205, 209, BLUE)
    arrow(c, 489, 260, 517, TEAL)
    arrow(c, 489, 186, 517, TEAL)
    arrow(c, 489, 130, 517, ORANGE)
    txt(c, 25, 37, 'The analyst reviews recommendations and supporting evidence before taking operational action.', 9.2, SUB)
    c.showPage()
    c.save()


def evidence_path():
    c = canvas.Canvas(str(HERE / 'evidence-path.pdf'), pagesize=(720, 263), pageCompression=1)
    c.setTitle('Sentinel KMS: article evidence continuity')
    txt(c, 25, 235, 'ARTICLE EVIDENCE CONTINUITY', 11, TEAL, 'Helvetica-Bold')
    txt(c, 25, 214, 'From publication to a reviewable passage', 16, INK, 'Helvetica-Bold')
    specs = [
        ('01', 'Published article', ['Administrative approval;', 'active lifecycle']),
        ('02', 'Revision + snapshot', ['Content hash and exact', 'passage boundaries']),
        ('03', 'Policy-filtered hit', ['Current grant, revision', 'and span checked']),
        ('04', 'Bound citation', ['Canonical identity saved', 'with assistant turn']),
        ('05', 'Evidence viewer', ['Resolve again using', 'current read policy']),
    ]
    for i, (n, title, lines) in enumerate(specs):
        x = 25 + i * 137
        box(c, x, 77, 122, 113, WHITE)
        c.setFillColor(TEAL if i < 3 else BLUE)
        c.roundRect(x + 10, 172, 25, 12, 3, fill=1, stroke=0)
        txt(c, x + 16, 175, n, 8.3, WHITE, 'Helvetica-Bold')
        txt(c, x + 10, 145, title, 10.3, INK, 'Helvetica-Bold')
        for j, line in enumerate(lines):
            txt(c, x + 10, 121 - j * 15, line, 9.3, SUB)
        if i < 4:
            arrow(c, x + 124, 130, x + 134, TEAL)
    c.setStrokeColor(FAINT)
    c.line(25, 60, 695, 60)
    txt(c, 25, 43, 'Identity: article ID  /  revision ID + number  /  snapshot ID  /  half-open span [start, end)', 9.4, INK, 'Helvetica-Bold')
    txt(c, 25, 24, 'Historical identity does not imply continued readability; source-only uploads follow a separate grounding path.', 9, SUB)
    c.showPage()
    c.save()


if __name__ == '__main__':
    architecture()
    evidence_path()
    for file in (HERE / 'architecture.pdf', HERE / 'evidence-path.pdf'):
        print(f'{file.name}: {file.stat().st_size} bytes')
