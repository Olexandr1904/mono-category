// Behaviour that used to live in inline `onclick` / `onchange` attributes.
//
// The delete button interpolated a category name straight into a JavaScript string
// literal. kotlinx.html escapes `<`, `>`, `&` and `"` in attribute values but not `'`,
// so a category named  x'); alert(1); ('  closed the confirm() argument and ran as code.
// Reading the text from a data- attribute at runtime keeps it data: it is never parsed
// as JavaScript, and the escaping kotlinx.html does perform is sufficient for it.
(function () {
    'use strict';

    // Delegated from the document so rows rendered on any page are covered without
    // per-element wiring.
    document.addEventListener('submit', function (event) {
        var submitter = event.submitter;
        var dataset = submitter && submitter.dataset ? submitter.dataset : null;
        var message = dataset ? dataset.confirm : null;

        // 2026-09-03 branch review, finding 6: the /settings accounts form's submit
        // button carries data-confirm-if-unchecked="active" instead of an unconditional
        // data-confirm — a save that leaves every account exactly as checked before
        // needs no prompt, only one that turns an account off does, since that's the one
        // that retroactively rewrites history. A checkbox's `.defaultChecked` reflects
        // how the server rendered it (i.e. the account's current `active` value), so
        // comparing it against `.checked` finds exactly the boxes this submission just
        // unchecked, without any extra state tracked in JS.
        if (!message && dataset && dataset.confirmIfUnchecked && event.target) {
            var groupName = dataset.confirmIfUnchecked;
            var boxes = event.target.querySelectorAll('input[type="checkbox"][name="' + groupName + '"]');
            for (var i = 0; i < boxes.length; i++) {
                if (boxes[i].defaultChecked && !boxes[i].checked) {
                    message = dataset.confirmMessage;
                    break;
                }
            }
        }

        if (message && !window.confirm(message)) {
            event.preventDefault();
        }
    });

    // Finding 5 (IMPORTANT) of the 2026-09-04 review: this comment used to claim the row
    // select "no longer carries data-submit-on-change at all" and describe a per-row apply
    // button that TransactionsPage.kt has not emitted since the redesign — both false, and
    // the three data- attributes that check (data-row-category, data-filter-category,
    // data-filter-uncategorized) matched nothing the app renders anymore. The real
    // invariant, current as of design-system.md conflict #1:
    //
    // The row's category <select> DOES carry data-submit-on-change and auto-submits on
    // every change, including Chrome's ArrowDown-fires-change-without-opening-the-dropdown
    // quirk. That is safe here because the form it submits posts to
    // POST /transactions/{id}/category -> IngestService.setTransactionCategory, which
    // touches this one row only — no MCC bind, no counterparty rule, no matter how the
    // change fired. A mis-keyed pick costs at most one row, recoverable by re-picking.
    // Binding an MCC or a counterparty across every matching row only ever happens from an
    // explicit click on "Застосувати" in the rule strip (TransactionsPage.kt's ruleBanner),
    // a separate form entirely, never wired to auto-submit.
    //
    // The two filter selects and the two filter toggle pills (now <a class="filter-chip">
    // links, not checkboxes) auto-submit too — picking a filter has no blast radius either.
    // The category-filter + "лише без категорії" collision finding 1 fixed lives entirely
    // server-side now (TransactionsPage.kt's get("/transactions") handler), so there is no
    // client-side pairing guard to keep in sync with the markup here.
    document.addEventListener('change', function (event) {
        var target = event.target;
        if (!target || !target.dataset) return;
        if (target.dataset.submitOnChange !== 'true' || !target.form) return;
        // requestSubmit() fires the submit event, so the confirm() handler above still
        // applies; form.submit() would bypass it.
        if (target.form.requestSubmit) {
            target.form.requestSubmit();
        } else {
            target.form.submit();
        }
    });
    // Bar widths come from a data- attribute, never a style= attribute in the markup.
    // The Content-Security-Policy sets style-src 'self' with no 'unsafe-inline', so the
    // browser discards an inline style attribute outright — every bar then rendered at
    // its container's full width and a category at 0% looked identical to one at 81%.
    // Assigning through the CSSOM here is not governed by style-src, so it works.
    // The stylesheet defaults these spans to zero width: if this script ever fails to
    // run, a bar shows nothing rather than confidently showing everything.
    function applyBarWidths(root) {
        var bars = (root || document).querySelectorAll('[data-bar-width]');
        for (var i = 0; i < bars.length; i++) {
            // parseFloat, not parseInt: the Огляд stacked bar's segments carry a
            // 2-decimal share (design-handoff.md §1, "width: <share>% (2 decimals)") so
            // a sliver segment doesn't get rounded away to nothing next to a much larger
            // one. Every existing whole-percent bar (limit bars, share bars) still parses
            // identically under parseFloat.
            var pct = parseFloat(bars[i].dataset.barWidth);
            if (!isNaN(pct)) bars[i].style.width = Math.max(0, Math.min(100, pct)) + '%';
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { applyBarWidths(); });
    } else {
        applyBarWidths();
    }
})();