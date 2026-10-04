"""
护工性别关键词降级逻辑自测（AI 不可用时的兜底路径）。

直接复用 OrderDispatchAgent 源码里的词表，避免"测试和实现不同步"。
运行：
    cd "C:/Users/Lenovo/Desktop/26-后端/Silver-后端"
    "D:/py3.13.9/python.exe" tools/test_gender_fallback.py
"""
import re
import io
import os

SRC = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'java',
                   'com', 'elderly', 'agent', 'OrderDispatchAgent.java')


def load_keywords(block_name, src):
    """从 Java 源码里抽出 Set.of(...) 的词表"""
    m = re.search(block_name + r' = java\.util\.Set\.of\((.*?)\);', src, re.S)
    if not m:
        raise SystemExit(f'未找到词表 {block_name}，源码结构可能变了')
    return re.findall(r'"([^"]+)"', m.group(1))


def decide(text, gender, intimacy, manual):
    """与 OrderDispatchAgent.decideGenderByKeyword 保持一致"""
    if any(k in text for k in intimacy):
        return (gender, 'must') if gender else (None, 'any')
    if any(k in text for k in manual):
        return ('男', 'prefer')
    return (None, 'any')


def main():
    src = io.open(SRC, encoding='utf-8').read()
    intimacy = load_keywords('INTIMACY_KW', src)
    manual = load_keywords('MANUAL_KW', src)
    print(f'隐私词 {len(intimacy)} 个 / 体力词 {len(manual)} 个\n')

    # (需求原文, 老人性别, 期望的 (requireGender, genderStrength))
    cases = [
        # 隐私类 → 同性别 must
        ('我腿脚不便，需要人帮我洗个澡', '女', ('女', 'must')),
        ('帮我洗个澡', '男', ('男', 'must')),
        ('需要人帮我擦擦身', '女', ('女', 'must')),
        ('帮我剪指甲', '女', ('女', 'must')),
        ('帮我翻个身', '女', ('女', 'must')),
        ('要换尿不湿了', '男', ('男', 'must')),
        ('扶我上厕所', '女', ('女', 'must')),
        # 体力类 → 男性 prefer
        ('客厅的灯泡坏了帮我换一个', '女', ('男', 'prefer')),
        ('厨房水管漏了', '女', ('男', 'prefer')),
        ('帮我把轮椅推到楼下', '女', ('男', 'prefer')),
        ('这个柜子我搬不动', '女', ('男', 'prefer')),
        # 普通照料 → 不限
        ('帮我买个西瓜', '女', (None, 'any')),
        ('陪我聊聊天', '男', (None, 'any')),
        ('明天陪我去医院复查', '女', (None, 'any')),
        ('帮我打扫卫生', '女', (None, 'any')),
        ('帮我做饭', '男', (None, 'any')),
        ('帮我遛个弯', '女', (None, 'any')),
        # 混合：隐私优先于体力
        ('洗个澡顺便换个灯泡', '女', ('女', 'must')),
        # 老人性别未知
        ('老人性别未知要洗澡', '', (None, 'any')),
    ]

    passed = failed = 0
    for text, gender, expect in cases:
        actual = decide(text, gender, intimacy, manual)
        ok = actual == expect
        passed += ok
        failed += not ok
        flag = 'PASS' if ok else 'FAIL'
        g = gender or '未知'
        print(f'{flag} {text!r:32} 老人={g:4} 期望={str(expect):18} 实际={actual}')

    print(f'\n通过 {passed} 条，失败 {failed} 条')
    return 1 if failed else 0


if __name__ == '__main__':
    raise SystemExit(main())
