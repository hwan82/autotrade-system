// 설정 화면 알림 시험 보내기(D-103). 흐름 실험실의 시험용 설정 폴더는 알림이 모두 꺼져 있어(기본값) 외부로 아무것도 보내지 않는다.
// 버튼을 누르면 채널마다 "보내지 않음"과 이유가 나오고, 바로 다시 누르면 10초 안이라 거절한다.
import { expect, test } from '@playwright/test';
import { FLOW_PORT } from '../support/paths.mjs';
import { watchErrors } from '../support/errors.mjs';

const BASE = `http://127.0.0.1:${FLOW_PORT}`;

test('알림 시험 보내기는 채널별 결과를 보이고 꺼진 채널은 보내지 않는다', async ({ page }) => {
  const errors = watchErrors(page);
  await page.goto(`${BASE}/#/settings`);
  const section = page.locator('section').filter({ has: page.getByRole('heading', { level: 2, name: '알림' }) });
  await expect(section.getByRole('heading', { level: 3, name: '알림 시험' })).toBeVisible();

  const button = section.getByRole('button', { name: '알림 시험 보내기' });
  await button.click();
  const table = section.getByRole('table', { name: '알림 시험 결과' });
  await expect(table.getByRole('row').filter({ hasText: 'Slack' })).toContainText('보내지 않음');
  await expect(table.getByRole('row').filter({ hasText: 'Slack' })).toContainText('꺼져 있어 보내지 않았습니다');
  await expect(table.getByRole('row').filter({ hasText: '카카오톡' })).toContainText('보내지 않음');
  await expect(page.locator('#toast')).toContainText('보낸 채널이 없습니다');
  await expect(button).toBeEnabled();

  // 끝난 뒤 바로 다시 누르면 서버가 거절하고(429) 앞 결과는 그대로 남는다
  errors.expectHttp(429);
  await button.click();
  await expect(page.locator('#toast')).toContainText('초 뒤에 다시 누르세요');
  await expect(table.getByRole('row').filter({ hasText: 'Slack' })).toContainText('보내지 않음');
  errors.expectNone();
});
