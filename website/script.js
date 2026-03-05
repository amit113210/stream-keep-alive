// =====================
// Stream Keep Alive — Landing Page Scripts
// =====================

// FAQ Accordion
function toggleFaq(element) {
    const isActive = element.classList.contains('active');
    document.querySelectorAll('.faq-item').forEach(item => {
        item.classList.remove('active');
    });
    if (!isActive) {
        element.classList.add('active');
    }
}

// Troubleshoot accordion (step 5)
function toggleTs(element) {
    const isActive = element.classList.contains('active');
    document.querySelectorAll('.ts-item').forEach(item => {
        item.classList.remove('active');
    });
    if (!isActive) {
        element.classList.add('active');
    }
}

// Install OS tabs
function showInstallOS(id) {
    document.querySelectorAll('.os-install-content').forEach(c => c.classList.remove('active'));
    document.querySelectorAll('.os-tab-sm').forEach(t => t.classList.remove('active'));
    document.getElementById(id).classList.add('active');
    event.target.classList.add('active');
}

// Copy to clipboard
function copyCommand(button) {
    const codeBlock = button.closest('.code-block');
    const codeEl = codeBlock.querySelector('code');
    if (!codeEl) return;

    const text = codeEl.textContent.trim();
    navigator.clipboard.writeText(text).then(() => {
        button.textContent = '✅';
        button.classList.add('copied');
        setTimeout(() => {
            button.textContent = '📋';
            button.classList.remove('copied');
        }, 2000);
    }).catch(() => {
        // Fallback for older browsers
        const textarea = document.createElement('textarea');
        textarea.value = text;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        document.execCommand('copy');
        document.body.removeChild(textarea);
        button.textContent = '✅';
        button.classList.add('copied');
        setTimeout(() => {
            button.textContent = '📋';
            button.classList.remove('copied');
        }, 2000);
    });
}

// Smooth scrolling for anchor links
document.querySelectorAll('a[href^="#"]').forEach(anchor => {
    anchor.addEventListener('click', function (e) {
        e.preventDefault();
        const target = document.querySelector(this.getAttribute('href'));
        if (target) {
            target.scrollIntoView({
                behavior: 'smooth',
                block: 'start'
            });
        }
    });
});

// Counter animation for stats
function animateCounter(element, target, duration = 2000) {
    let start = 0;
    const increment = target / (duration / 16);
    const isNumber = !isNaN(target);

    if (!isNumber) return;

    function update() {
        start += increment;
        if (start >= target) {
            element.textContent = target.toLocaleString();
            return;
        }
        element.textContent = Math.floor(start).toLocaleString();
        requestAnimationFrame(update);
    }

    update();
}

// Intersection Observer for scroll animations
const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
        if (entry.isIntersecting) {
            entry.target.style.opacity = '1';
            entry.target.style.transform = 'translateY(0)';

            // Animate download counter
            if (entry.target.id === 'stat-downloads') {
                animateCounter(entry.target, 1247);
            }
        }
    });
}, {
    threshold: 0.1,
    rootMargin: '0px 0px -50px 0px'
});

// Observe elements for scroll animation
document.addEventListener('DOMContentLoaded', () => {
    const sections = document.querySelectorAll('.feature-card, .step, .download-card, .faq-item, .trust-item, .ba-card, .changelog-item, .app-badge');
    sections.forEach((el, index) => {
        el.style.opacity = '0';
        el.style.transform = 'translateY(30px)';
        const delay = Math.min(index * 0.05, 0.3);
        el.style.transition = `all 0.6s ease ${delay}s`;
        observer.observe(el);
    });

    // Observe download counter
    const downloadStat = document.getElementById('stat-downloads');
    if (downloadStat) {
        observer.observe(downloadStat);
    }
});

// Header background on scroll
window.addEventListener('scroll', () => {
    const hero = document.querySelector('.hero');
    if (hero && window.scrollY > 100) {
        hero.style.backgroundPosition = `center ${window.scrollY * 0.3}px`;
    }
});
