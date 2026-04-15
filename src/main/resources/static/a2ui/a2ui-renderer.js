(function initA2uiRenderer(global) {
  function tryParseEnvelope(raw) {
    if (!raw) return null;
    try {
      const parsed = JSON.parse(raw);
      if (parsed && typeof parsed === 'object' && parsed.a2ui) {
        return parsed;
      }
      if (typeof parsed === 'string') {
        const nested = JSON.parse(parsed);
        if (nested && typeof nested === 'object' && nested.a2ui) {
          return nested;
        }
      }
      return null;
    } catch (e) {
      return null;
    }
  }

  function render(ai, envelope, options) {
    const protocol = envelope?.a2ui;
    if (!protocol || !ai?.a2ui) return false;
    const rendered = renderProtocol(protocol, options || {});
    if (!rendered) return false;
    ai.a2ui.classList.add('active');
    ai.a2ui.innerHTML = rendered.html;
    rendered.bind(ai.a2ui);
    return true;
  }

  function renderProtocol(protocol, options) {
    const messages = Array.isArray(protocol?.messages) ? protocol.messages : [];
    if (messages.length === 0) return null;
    const surfaces = new Map();

    function ensureSurface(surfaceId) {
      if (!surfaces.has(surfaceId)) {
        surfaces.set(surfaceId, { components: new Map(), dataModel: {}, root: '' });
      }
      return surfaces.get(surfaceId);
    }

    for (const msg of messages) {
      if (msg?.surfaceUpdate) {
        const surface = ensureSurface(msg.surfaceUpdate.surfaceId);
        const components = Array.isArray(msg.surfaceUpdate.components) ? msg.surfaceUpdate.components : [];
        components.forEach(c => surface.components.set(c.id, c.component));
      }
      if (msg?.dataModelUpdate) {
        const surface = ensureSurface(msg.dataModelUpdate.surfaceId);
        applyDataModelUpdate(surface.dataModel, msg.dataModelUpdate.path || '', msg.dataModelUpdate.contents || []);
      }
      if (msg?.beginRendering) {
        const surface = ensureSurface(msg.beginRendering.surfaceId);
        surface.root = msg.beginRendering.root || '';
      }
    }

    const firstSurface = Array.from(surfaces.values()).find(surface => surface.root);
    if (!firstSurface) return null;
    const bindings = [];
    const html = renderComponent(firstSurface, firstSurface.root, bindings, options);
    return {
      html,
      bind(rootEl) {
        bindings.forEach(fn => fn(rootEl));
      }
    };
  }

  function applyDataModelUpdate(model, path, contents) {
    const target = ensureModelPath(model, path);
    (Array.isArray(contents) ? contents : []).forEach(entry => {
      if (!entry || !entry.key) return;
      if (Object.prototype.hasOwnProperty.call(entry, 'valueString')) {
        target[entry.key] = entry.valueString;
      }
    });
  }

  function ensureModelPath(model, path) {
    const normalized = String(path || '').replace(/^\/+/, '');
    if (!normalized) return model;
    return normalized.split('/').filter(Boolean).reduce((acc, key) => {
      if (!acc[key] || typeof acc[key] !== 'object') {
        acc[key] = {};
      }
      return acc[key];
    }, model);
  }

  function getBoundValue(model, boundValue) {
    if (boundValue == null) return '';
    if (typeof boundValue !== 'object') return boundValue;
    if (typeof boundValue.literalString === 'string' && !boundValue.path) {
      return boundValue.literalString;
    }
    if (boundValue.path) {
      const resolved = resolveModelPath(model, boundValue.path);
      if (resolved !== undefined && resolved !== null && resolved !== '') return resolved;
      if (typeof boundValue.literalString === 'string') return boundValue.literalString;
    }
    return '';
  }

  function resolveModelPath(model, path) {
    const normalized = String(path || '').replace(/^\/+/, '');
    if (!normalized) return model;
    return normalized.split('/').filter(Boolean).reduce((acc, key) => {
      if (acc == null) return undefined;
      return acc[key];
    }, model);
  }

  function renderComponent(surface, componentId, bindings, options) {
    const componentWrapper = surface.components.get(componentId);
    if (!componentWrapper || typeof componentWrapper !== 'object') return '';
    const entries = Object.entries(componentWrapper);
    if (entries.length === 0) return '';
    const [type, props] = entries[0];
    if (type === 'Column') {
      const children = props?.children?.explicitList || [];
      return children.map(childId => renderComponent(surface, childId, bindings, options)).join('');
    }
    if (type === 'ProductOverviewCard') {
      return renderProductOverviewCard(props?.data || {});
    }
    if (type === 'ReservationForm') {
      return renderReservationForm(surface, componentId, props || {}, bindings, options);
    }
    return '';
  }

  function renderProductOverviewCard(data) {
    const included = Array.isArray(data.includedItems) ? data.includedItems : [];
    const optional = Array.isArray(data.optionalItems) ? data.optionalItems : [];
    const timeline = Array.isArray(data.timeline) ? data.timeline : [];
    const notices = Array.isArray(data.noticeItems) ? data.noticeItems : [];
    const thumb = (data.thumbnailUrl || '').trim();
    return `
      <section class="a2ui-card">
        <header class="a2ui-card-header">
          <div class="a2ui-media-head">
            <div class="a2ui-thumb">
              ${thumb ? `<img src="${escapeHtml(thumb)}" alt="${escapeHtml(data.name || '상품 대표 이미지')}" loading="lazy" />` : ''}
            </div>
            <div class="a2ui-head-copy">
              <div class="a2ui-card-code">${escapeHtml(data.productCode || '')}</div>
              <div class="a2ui-card-title">${escapeHtml(data.name || '상품 상세')}</div>
            </div>
          </div>
        </header>
        <div class="a2ui-card-body">
          <div class="a2ui-grid">
            <div class="a2ui-stat"><div class="a2ui-stat-label">출발</div><div class="a2ui-stat-value">${escapeHtml(data.departureDate || '-')}</div></div>
            <div class="a2ui-stat"><div class="a2ui-stat-label">도착</div><div class="a2ui-stat-value">${escapeHtml(data.arrivalDate || '-')}</div></div>
            <div class="a2ui-stat"><div class="a2ui-stat-label">여행기간</div><div class="a2ui-stat-value">${escapeHtml(data.nights || 0)}박 ${escapeHtml(data.days || 0)}일</div></div>
            <div class="a2ui-stat"><div class="a2ui-stat-label">성인 기준가</div><div class="a2ui-stat-value">${escapeHtml(formatMoney(data.price, data.currency))}</div></div>
          </div>
          <div class="a2ui-chip-row">
            ${data.departureCity ? `<span class="a2ui-chip">${escapeHtml(data.departureCity)}</span>` : ''}
            ${data.arrivalCity ? `<span class="a2ui-chip">${escapeHtml(data.arrivalCity)}</span>` : ''}
            ${data.theme ? `<span class="a2ui-chip">${escapeHtml(data.theme)}</span>` : ''}
            ${data.brand ? `<span class="a2ui-chip">${escapeHtml(data.brand)}</span>` : ''}
            ${data.airline ? `<span class="a2ui-chip">${escapeHtml(data.airline)}</span>` : ''}
          </div>
          <section class="a2ui-section">
            <div class="a2ui-section-title">가격 정보</div>
            <div class="a2ui-grid">
              <div class="a2ui-stat"><div class="a2ui-stat-label">성인</div><div class="a2ui-stat-value">${escapeHtml(formatMoney(data.adultPrice, 'KRW'))}</div></div>
              <div class="a2ui-stat"><div class="a2ui-stat-label">아동</div><div class="a2ui-stat-value">${escapeHtml(formatMoney(data.childPrice, 'KRW'))}</div></div>
              <div class="a2ui-stat"><div class="a2ui-stat-label">유아</div><div class="a2ui-stat-value">${escapeHtml(formatMoney(data.infantPrice, 'KRW'))}</div></div>
              <div class="a2ui-stat"><div class="a2ui-stat-label">계약금</div><div class="a2ui-stat-value">${escapeHtml(formatMoney(data.depositPrice, 'KRW'))}</div></div>
            </div>
            ${data.singleRoomNote ? `<div class="a2ui-inline-meta">1인 객실: ${escapeHtml(data.singleRoomNote)}</div>` : ''}
            ${included.length > 0 ? `<ul class="a2ui-list">${included.map(item => `<li>${escapeHtml([item.category, item.description].filter(Boolean).join(' '))}</li>`).join('')}</ul>` : ''}
            ${optional.length > 0 ? `<ul class="a2ui-list">${optional.map(item => `<li>${escapeHtml([item.category, item.description].filter(Boolean).join(' '))}</li>`).join('')}</ul>` : ''}
          </section>
          <section class="a2ui-section">
            <div class="a2ui-section-title">일정 정보</div>
            ${(data.meetingDate || data.meetingTime || data.meetingAirport) ? `<div class="a2ui-inline-meta">미팅: ${escapeHtml([data.meetingDate, data.meetingTime, data.meetingAirport].filter(Boolean).join(' / '))}</div>` : ''}
            ${timeline.length > 0 ? `<ul class="a2ui-list">${timeline.map(item => `<li>${escapeHtml([`${item.day || 0}일차`, item.date, item.dayOfWeek].filter(Boolean).join(' '))}${item.hotelName ? ` · 숙소: ${escapeHtml(item.hotelName)}` : ''}${item.hotelLocation ? ` (${escapeHtml(item.hotelLocation)})` : ''}</li>`).join('')}</ul>` : '<div class="a2ui-inline-meta">일정 정보가 없습니다.</div>'}
          </section>
          <section class="a2ui-section">
            <div class="a2ui-section-title">규정 및 안내</div>
            ${notices.length > 0 ? `<ul class="a2ui-list">${notices.map(item => `<li><strong>${escapeHtml(item.title || '')}</strong> ${escapeHtml(item.content || '')}</li>`).join('')}</ul>` : '<div class="a2ui-inline-meta">추가 안내 정보가 없습니다.</div>'}
          </section>
        </div>
      </section>
    `;
  }

  function renderReservationForm(surface, componentId, props, bindings, options) {
    const title = getBoundValue(surface.dataModel, props.title);
    const fields = Array.isArray(props.fields) ? props.fields : [];
    const fieldsHtml = fields.map(field => {
      const valueBinding = field?.value || {};
      const initialValue = getBoundValue(surface.dataModel, valueBinding);
      if (valueBinding?.path) {
        const current = resolveModelPath(surface.dataModel, valueBinding.path);
        if (current === undefined) {
          setModelValue(surface.dataModel, valueBinding.path, initialValue);
        }
      }
      return `
        <div class="a2ui-field">
          <label>${escapeHtml(getBoundValue(surface.dataModel, field.label))}</label>
          <input
            class="a2ui-input"
            data-field-path="${escapeHtml(valueBinding.path || '')}"
            data-field-name="${escapeHtml(field.name || '')}"
            type="${escapeHtml(field.inputType || 'text')}"
            value="${escapeHtml(initialValue || '')}"
            placeholder="${escapeHtml(getBoundValue(surface.dataModel, field.placeholder) || '')}"
            ${field.inputType === 'number' ? 'min="1" step="1"' : ''}
          />
        </div>
      `;
    }).join('');

    bindings.push(rootEl => {
      rootEl.querySelectorAll(`[data-form-component="${componentId}"] [data-field-path]`).forEach(input => {
        input.addEventListener('input', event => {
          setModelValue(surface.dataModel, event.target.getAttribute('data-field-path') || '', event.target.value);
        });
      });
      const form = rootEl.querySelector(`[data-form-component="${componentId}"]`);
      if (!form) return;
      form.addEventListener('submit', event => {
        event.preventDefault();
        const action = props.action || {};
        const context = Array.isArray(action.context) ? action.context : [];
        const resolved = {};
        context.forEach(entry => {
          if (!entry?.key) return;
          resolved[entry.key] = getBoundValue(surface.dataModel, entry.value);
        });
        if (action.name === 'submit_reservation') {
          const prompt = buildReservationCreatePrompt(
            { productCode: resolved.productCode },
            {
              bookerName: resolved.bookerName,
              contact: resolved.contact,
              headCount: resolved.headCount,
              birthDate: resolved.birthDate
            }
          );
          if (prompt && typeof options?.onReservationSubmit === 'function') {
            options.onReservationSubmit(prompt);
          }
        }
      });
    });

    return `
      <section class="a2ui-section">
        <div class="a2ui-section-title">${escapeHtml(title || '예약 생성')}</div>
        <form class="a2ui-form" data-form-component="${escapeHtml(componentId)}">
          <div class="a2ui-form-grid">
            ${fieldsHtml}
          </div>
          <button class="a2ui-submit" type="submit">예약 생성</button>
        </form>
      </section>
    `;
  }

  function setModelValue(model, path, value) {
    const normalized = String(path || '').replace(/^\/+/, '');
    if (!normalized) return;
    const parts = normalized.split('/').filter(Boolean);
    let cursor = model;
    for (let i = 0; i < parts.length - 1; i += 1) {
      const key = parts[i];
      if (!cursor[key] || typeof cursor[key] !== 'object') {
        cursor[key] = {};
      }
      cursor = cursor[key];
    }
    cursor[parts[parts.length - 1]] = value;
  }

  function buildReservationCreatePrompt(data, booking) {
    const productCode = data?.productCode || '';
    if (!productCode) return '';
    return [
      '예약생성해줘',
      `상품코드: ${productCode}`,
      `예약자: ${booking.bookerName || ''}`,
      `연락처: ${booking.contact || ''}`,
      `인원수: ${booking.headCount || ''}`,
      `생년월일: ${booking.birthDate || ''}`
    ].join('\n');
  }

  function formatMoney(amount, currency) {
    const numeric = Number(amount);
    if (!Number.isFinite(numeric)) return '-';
    if ((currency || 'KRW') === 'KRW') {
      return `${numeric.toLocaleString('ko-KR')}원`;
    }
    return `${numeric.toLocaleString('ko-KR')} ${currency || ''}`.trim();
  }

  function escapeHtml(value) {
    return String(value ?? '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  global.A2uiRenderer = {
    tryParseEnvelope,
    render
  };
}(window));
