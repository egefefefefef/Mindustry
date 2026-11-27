'use strict';

const API = 'https://api.github.com/repos/egefefefefef/Mindustry/releases?per_page=100';
const REPO = 'https://github.com/egefefefefef/Mindustry/releases';
const $ = id => document.getElementById(id);
const element = (tag, className, text) => {
  const node = document.createElement(tag);
  if(className) node.className = className;
  if(text != null) node.textContent = text;
  return node;
};

// Release content is untrusted. Only GitHub links are used; descriptions are
// rendered with textContent rather than interpreted as HTML or JavaScript.
function githubUrl(value, fallback = REPO){
  try{
    const url = new URL(value);
    if(url.protocol === 'https:' && url.hostname === 'github.com') return url.href;
  }catch(_){}
  return fallback;
}
function externalLink(url, className, text){
  const link = element('a', className, text);
  link.href = githubUrl(url);
  link.target = '_blank';
  link.rel = 'noopener noreferrer';
  return link;
}
function date(value){
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? 'Дата неизвестна' : new Intl.DateTimeFormat('ru-RU', {day:'numeric', month:'long', year:'numeric', timeZone:'UTC'}).format(parsed);
}
function size(value){
  return Number.isFinite(value) && value > 0 ? `${(value / 1024 / 1024).toFixed(0)} МБ` : '';
}
function platform(name){
  if(/windows/i.test(name)) return ['Windows', '▦', /x64|amd64/i.test(name) ? 'x64' : ''];
  if(/linux/i.test(name)) return ['Linux', '◈', /x64|amd64/i.test(name) ? 'x64' : ''];
  if(/macos|darwin|osx/i.test(name)) return ['macOS', '⌘', 'Intel / Apple Silicon'];
  if(/android|\.apk$/i.test(name)) return ['Android', '◇', ''];
  if(/ios/i.test(name)) return ['iOS', '◇', ''];
  return [name, '↓', ''];
}
function assets(release){ return Array.isArray(release.assets) ? release.assets : []; }
function description(container, body){
  const lines = (body || 'Описание для этого релиза не добавлено.').split(/\r?\n/);
  let list = null;
  for(const raw of lines){
    const line = raw.trim();
    if(!line){ list = null; continue; }
    if(/^#{1,6}\s/.test(line)){
      list = null;
      container.append(element('h4', '', line.replace(/^#{1,6}\s+/, '')));
    }else if(/^[-*]\s/.test(line)){
      if(!list){ list = element('ul'); container.append(list); }
      list.append(element('li', '', line.replace(/^[-*]\s+/, '').replace(/`/g, '')));
    }else{
      list = null;
      container.append(element('p', '', line.replace(/`/g, '')));
    }
  }
}
function renderLatest(release){
  $('latest-version').textContent = release.tag_name || 'Релиз';
  $('latest-date').textContent = date(release.published_at);
  $('status-date').textContent = date(release.published_at).toUpperCase();
  const box = $('latest-downloads');
  box.replaceChildren();
  if(!assets(release).length){ box.append(element('div', 'loading', 'В этом релизе нет файлов.')); return; }
  for(const asset of assets(release)){
    const [name, icon, arch] = platform(asset.name || 'Архив');
    const a = externalLink(asset.browser_download_url, 'download-link');
    a.setAttribute('aria-label', `Скачать ${asset.name || name}`);
    a.append(element('span', 'platform-icon', icon));
    const label = element('span', 'download-platform', name);
    label.append(element('span', 'download-meta', [arch, size(asset.size)].filter(Boolean).join(' · ') || asset.name));
    a.append(label, element('span', 'download-arrow', '↓'));
    box.append(a);
  }
}
function renderVersions(releases, query = ''){
  const box = $('versions');
  box.replaceChildren();
  const filtered = releases.filter(r => `${r.tag_name || ''} ${r.name || ''}`.toLowerCase().includes(query.toLowerCase().trim()));
  if(!filtered.length){ box.append(element('div', 'loading', 'Версии не найдены.')); return; }
  for(const release of filtered){
    const row = element('details', 'version-row');
    const summary = element('summary', 'version-summary');
    summary.append(element('strong', '', release.tag_name || release.name || 'Релиз'));
    if(release === releases.find(r => !r.prerelease)) summary.append(element('span', 'version-badge', 'АКТУАЛЬНАЯ'));
    if(release.prerelease) summary.append(element('span', 'version-badge', 'ПРЕДРЕЛИЗ'));
    summary.append(element('span', 'version-date', date(release.published_at)), element('span', 'plus', '+'));
    const links = element('div', 'version-assets');
    for(const asset of assets(release)) links.append(externalLink(asset.browser_download_url, 'asset-chip', `${asset.name} ↓`));
    if(!assets(release).length) links.append(element('span', 'loading', 'Файлы не прикреплены.'));
    links.append(externalLink(release.html_url, 'asset-chip', 'Страница релиза ↗'));
    row.append(summary, links); box.append(row);
  }
}
function renderNews(releases){
  const box = $('news-list');
  box.replaceChildren();
  for(const release of releases){
    const article = element('article', 'news-item');
    const when = element('div', 'news-date');
    when.append(element('span', '', 'ОБНОВЛЕНИЕ'), document.createTextNode(date(release.published_at)));
    const content = element('div');
    const heading = element('div', 'news-title');
    heading.append(element('h3', '', release.name || release.tag_name || 'Релиз'), externalLink(release.html_url, '', 'НА GITHUB ↗'));
    const body = element('div', 'news-body');
    description(body, release.body);
    content.append(heading, body); article.append(when, content); box.append(article);
  }
}
async function liveReleases(){
  let url = API;
  const all = [];
  // Follow GitHub's pagination, including older versions, without trusting an
  // arbitrary Link header host. A reasonable cap avoids an endless loop.
  for(let page = 0; url && page < 100; page++){
    const response = await fetch(url, {headers:{Accept:'application/vnd.github+json'}});
    if(!response.ok) throw new Error(`GitHub API: ${response.status}`);
    const data = await response.json();
    if(!Array.isArray(data)) throw new Error('Unexpected releases response');
    all.push(...data);
    const next = (response.headers.get('Link') || '').split(',').find(part => /rel="next"/.test(part));
    const match = next && next.match(/<([^>]+)>/);
    if(!match) url = null;
    else{
      const candidate = new URL(match[1]);
      if(candidate.protocol !== 'https:' || candidate.hostname !== 'api.github.com' || candidate.pathname !== '/repos/egefefefefef/Mindustry/releases') throw new Error('Invalid pagination');
      url = candidate.href;
    }
  }
  return all;
}
async function init(){
  let releases;
  try{ releases = await liveReleases(); }
  catch(error){
    console.warn('GitHub releases are unavailable; using the bundled snapshot.', error);
    try{
      const response = await fetch('releases.json');
      if(!response.ok) throw new Error('Snapshot unavailable');
      releases = await response.json();
      const status = document.querySelector('.status-middle');
      status.textContent = 'ПОКАЗАНА СОХРАНЁННАЯ КОПИЯ РЕЛИЗОВ';
    }catch(_){
      $('latest-date').textContent = 'Не удалось загрузить релизы.';
      for(const id of ['latest-downloads', 'versions', 'news-list']){
        const fallback = externalLink(REPO, 'github-releases', 'ОТКРЫТЬ РЕЛИЗЫ НА GITHUB ↗');
        $(id).replaceChildren(fallback);
      }
      return;
    }
  }
  if(!Array.isArray(releases)) releases = [];
  releases = releases.filter(r => r && !r.draft && typeof r.tag_name === 'string');
  $('release-count').textContent = releases.length;
  if(!releases.length){
    $('latest-date').textContent = 'Опубликованных релизов пока нет.';
    $('latest-downloads').replaceChildren();
    $('versions').replaceChildren(element('div', 'loading', 'Пока нет версий.'));
    $('news-list').replaceChildren(element('div', 'loading', 'Пока нет новостей.'));
    return;
  }
  const latest = releases.find(r => !r.prerelease) || releases[0];
  renderLatest(latest);
  renderVersions(releases);
  renderNews(releases);
  $('search').addEventListener('input', event => renderVersions(releases, event.target.value));
}
init();

const FEEDBACK_ISSUES = 'https://api.github.com/repos/egefefefefef/Mindustry/issues?state=all&per_page=100';
const FEEDBACK_GITHUB = 'https://github.com/egefefefefef/Mindustry/issues';
function initFeedback(){
  const form = $('feedback-form');
  $('feedback-text').addEventListener('input', () => $('feedback-text').setCustomValidity(''));
  form.addEventListener('submit', event => {
    const rating = $('feedback-rating').value;
    const version = $('feedback-version').value.trim().replace(/[\r\n]+/g, ' ') || 'Не указана';
    const review = $('feedback-text').value.trim();
    if(review.length < 10){
      event.preventDefault();
      $('feedback-text').setCustomValidity('Напишите не менее 10 символов, не считая пробелы по краям.');
      $('feedback-text').reportValidity();
      return;
    }
    $('feedback-title').value = `[Отзыв] ${rating}/5`;
    $('feedback-body').value = `Оценка: ${rating} / 5\nВерсия игры: ${version}\n\n### Отзыв\n\n${review}`;
  });
  loadFeedback();
}
async function loadFeedback(){
  const box = $('feedback-list');
  try{
    const response = await fetch(FEEDBACK_ISSUES, {headers:{Accept:'application/vnd.github+json'}});
    if(!response.ok) throw new Error(`GitHub API: ${response.status}`);
    const issues = await response.json();
    if(!Array.isArray(issues)) throw new Error('Unexpected issues response');
    const reviews = issues.filter(issue => !issue.pull_request && /^\[Отзыв\]/i.test(issue.title || '')).slice(0, 5);
    box.replaceChildren();
    if(!reviews.length){ box.append(element('div', 'feedback-status', 'Отзывов пока нет. Будь первым, кто поделится впечатлениями!')); return; }
    for(const review of reviews){
      const article = element('article', 'feedback-review');
      const head = element('div', 'feedback-review-head');
      head.append(element('strong', '', review.user?.login || 'Игрок'), element('span', '', date(review.created_at)));
      const stars = (review.title || '').match(/\b([1-5])\s*\/\s*5\b/);
      article.append(head);
      if(stars) article.append(element('div', 'feedback-stars', '★'.repeat(Number(stars[1])) + '☆'.repeat(5 - Number(stars[1]))));
      const text = (review.body || '').split(/### Отзыв\s*/i).pop().trim();
      const snippet = text.length > 300 ? text.slice(0, 300) + '…' : text;
      article.append(element('p', '', snippet || 'Открыть отзыв на GitHub.'), externalLink(review.html_url, '', 'ЧИТАТЬ НА GITHUB ↗'));
      box.append(article);
    }
  }catch(error){
    console.warn('Feedback is unavailable.', error);
    const status = element('div', 'feedback-status', 'Не удалось загрузить отзывы. Возможно, раздел GitHub Issues ещё не включён или GitHub временно недоступен. ');
    status.append(externalLink(FEEDBACK_GITHUB, '', 'Открыть GitHub ↗'));
    box.replaceChildren(status);
  }
}
initFeedback();
