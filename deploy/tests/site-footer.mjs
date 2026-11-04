import assert from 'node:assert/strict';

export async function assertSharedFooter(page) {
    const footer = page.locator('#portfolio > .site-footer');
    assert.equal(await page.locator('footer').count(), 1);
    assert.deepEqual(await footer.locator('a').allTextContents(), ['~/itzephir', 'Telegram', 'GitHub', 'Email']);
    assert.deepEqual(await footer.locator('a').evaluateAll(links => links.map(a => a.getAttribute('href'))),
        ['/', 'https://t.me/ItzEphir', 'https://github.com/itzephir', 'mailto:d.y.dvoryannikov@mail.ru']);
    return footer.evaluate(element => {
        const rect = element.getBoundingClientRect();
        if (getComputedStyle(element).borderTopWidth !== '1px') throw new Error('Footer needs its shared top divider');
        const rounded = value => Math.round(value * 100) / 100;
        return [...element.querySelectorAll('a')].map(link => {
            const r = link.getBoundingClientRect();
            if (r.height < 44) throw new Error('Footer links need 44 px touch targets');
            if (r.left < rect.left - 0.03 || r.right > rect.right + 0.03) throw new Error('Footer link overflows');
            return [link.textContent, ...[r.x, r.y - rect.y, r.width, r.height].map(rounded)];
        });
    });
}
