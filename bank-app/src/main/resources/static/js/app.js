/*
 * Украшения интерфейса. Ничего обязательного для сценариев здесь нет:
 * все формы работают и без JavaScript, чтобы их корректно записывал рекордер JMeter.
 */
(function () {
    'use strict';

    // Блокируем кнопку после отправки формы, чтобы не было двойных платежей по двойному клику.
    // Класс, а не атрибут disabled: иначе имя и значение кнопки не уйдут на сервер.
    // Повторную отправку той же формы (например, Enter ещё раз) просто отменяем.
    document.addEventListener('submit', function (event) {
        var form = event.target;
        if (!form || form.tagName !== 'FORM') {
            return;
        }
        if (form.getAttribute('data-sent') === 'true') {
            event.preventDefault();
            return;
        }
        form.setAttribute('data-sent', 'true');
        var button = form.querySelector('button[type="submit"], input[type="submit"]');
        if (button) {
            window.setTimeout(function () {
                button.classList.add('is-busy');
                button.setAttribute('aria-busy', 'true');
            }, 0);
        }
    }, true);

    // Возврат на страницу кнопкой «Назад» достаёт её из кэша браузера вместе с блокировкой —
    // снимаем её, чтобы форму можно было отправить снова.
    window.addEventListener('pageshow', function () {
        document.querySelectorAll('form[data-sent]').forEach(function (form) {
            form.removeAttribute('data-sent');
        });
        document.querySelectorAll('.is-busy').forEach(function (button) {
            button.classList.remove('is-busy');
            button.removeAttribute('aria-busy');
        });
    });
})();
